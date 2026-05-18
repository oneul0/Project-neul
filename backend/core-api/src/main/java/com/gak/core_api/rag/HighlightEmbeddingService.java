package com.gak.core_api.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.core_api.domain.chat.entity.VodHighlight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 저장된 하이라이트에 대해 임베딩 텍스트를 생성하고 Ollama로 임베딩 벡터를 요청하여
 * vod_highlights.embedding 컬럼에 저장한다.
 *
 * 임베딩 텍스트는 채널 규모에 무관한 비율 기반 표현을 사용해 cross-channel 유사도 비교를 가능하게 한다.
 */
@Slf4j
@Service
public class HighlightEmbeddingService {

    private final WebClient webClient;
    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    @Value("${app.ollama.embed-url}")
    private String embedUrl;

    @Value("${app.ollama.embed-model}")
    private String embedModel;

    public HighlightEmbeddingService(WebClient.Builder webClientBuilder,
                                     DatabaseClient databaseClient,
                                     ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<VodHighlight> embedAndStore(VodHighlight highlight) {
        String embeddingText = buildEmbeddingText(highlight);

        return requestEmbedding(embeddingText)
                .flatMap(vector -> storeEmbedding(highlight.getId(), embeddingText, vector))
                .thenReturn(highlight)
                .onErrorResume(e -> {
                    log.warn("[Embedding] Failed for highlightId={}: {}", highlight.getId(), e.getMessage());
                    return Mono.just(highlight);
                });
    }

    /**
     * 채널 규모 무관 임베딩 소스 텍스트.
     * 절대 수치 없이 비율과 레이블만 사용해 cross-channel 유사도 비교를 가능하게 한다.
     */
    String buildEmbeddingText(VodHighlight h) {
        return String.format(
                "[%s] %s\ndominant=%s density=%.1fx unique=%.2f\n" +
                "signal: hype=%.2f laugh=%.2f surprise=%.2f tension=%.2f\n" +
                "keywords: %s",
                safe(h.getSceneLabel(), "unknown"),
                safe(h.getCategory(), "unknown"),
                safe(h.getEmotionDominance(), "neutral"),
                nullToZero(h.getDensityRatio()),
                nullToZero(h.getUniqueUserRatio()),
                nullToZero(h.getHypeRatio()),
                nullToZero(h.getLaughRatio()),
                nullToZero(h.getSurpriseRatio()),
                nullToZero(h.getTensionRatio()),
                safe(h.getKeywordSummary(), "")
        );
    }

    /** 외부(RetrievalService)에서 쿼리 벡터 생성 시 재사용 */
    public Mono<float[]> requestEmbeddingPublic(String text) {
        return requestEmbedding(text);
    }

    @SuppressWarnings("unchecked")
    private Mono<float[]> requestEmbedding(String text) {
        Map<String, String> body = Map.of("model", embedModel, "prompt", text);

        return webClient.post()
                .uri(embedUrl)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    List<Number> raw = (List<Number>) response.get("embedding");
                    float[] vector = new float[raw.size()];
                    for (int i = 0; i < raw.size(); i++) {
                        vector[i] = raw.get(i).floatValue();
                    }
                    return vector;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> storeEmbedding(Long highlightId, String embeddingText, float[] vector) {
        return databaseClient.sql(
                        "UPDATE vod_highlights SET embedding_text = :text, embedding = :vec::vector WHERE id = :id")
                .bind("text", embeddingText)
                .bind("vec", PgVectorUtils.toLiteral(vector))
                .bind("id", highlightId)
                .then();
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static double nullToZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
