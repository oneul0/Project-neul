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

    @Value("${app.ollama.generate-url}")
    private String generateUrl;

    @Value("${app.ollama.generate-model}")
    private String generateModel;

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

    /**
     * Ollama generative 모델로 현재 채팅 상황 해석 문장 생성.
     * 실패하면 fallback 문장 반환.
     */
    @SuppressWarnings("unchecked")
    public Mono<String> generateInsight(String prompt) {
        Map<String, Object> body = Map.of(
                "model", generateModel,
                "prompt", prompt,
                "stream", false
        );

        return webClient.post()
                .uri(generateUrl)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    String raw = (String) response.get("response");
                    if (raw == null || raw.isBlank()) return null;
                    return sanitizeInsight(raw);
                })
                .onErrorResume(e -> {
                    log.warn("[Embedding] generateInsight failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic());
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

    /** LLM 출력에서 공문서 말투·불필요한 수식어 정리 */
    private static String sanitizeInsight(String raw) {
        String s = raw.strip()
                .replaceAll("^\"|\"$", "")   // 앞뒤 따옴표
                .replace("\n", " ")           // 줄바꿈
                .strip();

        // "출력:" 이후 내용만 추출
        int outIdx = s.lastIndexOf("출력:");
        if (outIdx >= 0) s = s.substring(outIdx + 3).strip();

        // 가장 짧은 첫 줄만
        String firstLine = s.split("\n")[0].strip();
        if (!firstLine.isBlank()) s = firstLine;

        // 공문서 말투 → 구어체 변환
        s = s.replace("것으로 확인되었습니다", "고 있어요")
             .replace("것으로 보입니다", "고 있어요")
             .replace("것으로 판단됩니다", "고 있어요")
             .replace("하고 있는 것으로", "하고 있어요")
             .replace("있습니다", "있어요")
             .replace("됩니다", "돼요")
             .replace("합니다", "해요");

        // 너무 길면 자르기 (30자)
        if (s.length() > 30) s = s.substring(0, 30).strip() + "…";

        return s.isBlank() ? null : s;
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static double nullToZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
