package com.neul.core_api.rag;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 하이라이트 추천 — 3전략 혼합 검색.
 *
 * A (60%) 동질성: 같은 카테고리, 코사인 유사도 상위 → 채널/장르 내 패턴 활용
 * B (20%) 장르 탐색: 다른 카테고리, 고점수 하이라이트 → 장르 간 다양성
 * C (20%) 사용자 탐색: 다른 videoNo, 유사 임베딩 → 크로스-채널 패턴
 *
 * 비율은 설정값이므로 추후 A/B 테스트 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HighlightRetrievalService {

    private static final double RATIO_A = 0.6;
    private static final double RATIO_B = 0.2;
    private static final double RATIO_C = 0.2;

    private final DatabaseClient databaseClient;
    private final HighlightEmbeddingService embeddingService;

    /**
     * 주어진 후보 하이라이트와 유사한 과거 사례를 혼합 전략으로 검색한다.
     *
     * @param candidate  임베딩 소스를 만들 현재 하이라이트 후보
     * @param totalK     반환할 총 결과 수
     * @return 중복 제거된 혼합 추천 목록
     */
    public Mono<List<VodHighlight>> retrieve(VodHighlight candidate, int totalK) {
        String embeddingText = embeddingService.buildEmbeddingText(candidate);

        return embeddingService.requestEmbeddingPublic(embeddingText)
                .flatMap(vector -> {
                    String pgVec = toPgVectorLiteral(vector);
                    int kA = Math.max(1, (int) Math.round(totalK * RATIO_A));
                    int kB = Math.max(1, (int) Math.round(totalK * RATIO_B));
                    int kC = totalK - kA - kB;

                    Mono<List<VodHighlight>> strategyA = queryStrategyA(pgVec, candidate.getCategory(), candidate.getVideoNo(), kA);
                    Mono<List<VodHighlight>> strategyB = queryStrategyB(candidate.getCategory(), candidate.getVideoNo(), kB);
                    Mono<List<VodHighlight>> strategyC = queryStrategyC(pgVec, candidate.getVideoNo(), kC);

                    return Mono.zip(strategyA, strategyB, strategyC)
                            .map(tuple -> merge(tuple.getT1(), tuple.getT2(), tuple.getT3(), totalK));
                })
                .onErrorResume(e -> {
                    log.warn("[Retrieval] Failed to retrieve similar highlights: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** A: 같은 카테고리, 코사인 유사도 상위 (현재 videoNo 제외) */
    private Mono<List<VodHighlight>> queryStrategyA(String pgVec, String category, String excludeVideoNo, int k) {
        return databaseClient.sql("""
                        SELECT id, video_no, start_seconds, end_seconds, scene_label, category,
                               reason_summary, emotion_dominance, density_ratio,
                               hype_ratio, laugh_ratio, surprise_ratio, tension_ratio,
                               keyword_summary, highlight_score,
                               1 - (embedding <=> :vec::vector) AS similarity
                        FROM vod_highlights
                        WHERE embedding IS NOT NULL
                          AND category = :category
                          AND video_no != :excludeVideoNo
                        ORDER BY embedding <=> :vec::vector
                        LIMIT :k
                        """)
                .bind("vec", pgVec)
                .bind("category", safeCategory(category))
                .bind("excludeVideoNo", excludeVideoNo)
                .bind("k", k)
                .map(this::mapRow)
                .all()
                .collectList();
    }

    /** B: 다른 카테고리, highlight_score 상위 — 장르 다양성 */
    private Mono<List<VodHighlight>> queryStrategyB(String category, String excludeVideoNo, int k) {
        return databaseClient.sql("""
                        SELECT id, video_no, start_seconds, end_seconds, scene_label, category,
                               reason_summary, emotion_dominance, density_ratio,
                               hype_ratio, laugh_ratio, surprise_ratio, tension_ratio,
                               keyword_summary, highlight_score
                        FROM vod_highlights
                        WHERE embedding IS NOT NULL
                          AND category != :category
                          AND video_no != :excludeVideoNo
                        ORDER BY highlight_score DESC
                        LIMIT :k
                        """)
                .bind("category", safeCategory(category))
                .bind("excludeVideoNo", excludeVideoNo)
                .bind("k", k)
                .map(this::mapRow)
                .all()
                .collectList();
    }

    /** C: 다른 videoNo, 코사인 유사도 상위 — 크로스-채널 패턴 */
    private Mono<List<VodHighlight>> queryStrategyC(String pgVec, String excludeVideoNo, int k) {
        return databaseClient.sql("""
                        SELECT id, video_no, start_seconds, end_seconds, scene_label, category,
                               reason_summary, emotion_dominance, density_ratio,
                               hype_ratio, laugh_ratio, surprise_ratio, tension_ratio,
                               keyword_summary, highlight_score,
                               1 - (embedding <=> :vec::vector) AS similarity
                        FROM vod_highlights
                        WHERE embedding IS NOT NULL
                          AND video_no != :excludeVideoNo
                        ORDER BY embedding <=> :vec::vector
                        LIMIT :k
                        """)
                .bind("vec", pgVec)
                .bind("excludeVideoNo", excludeVideoNo)
                .bind("k", k)
                .map(this::mapRow)
                .all()
                .collectList();
    }

    /** A → B → C 순서로 삽입하되 id 기준 중복 제거, totalK 이하로 자른다. */
    private List<VodHighlight> merge(List<VodHighlight> a, List<VodHighlight> b, List<VodHighlight> c, int totalK) {
        Map<Long, VodHighlight> seen = new LinkedHashMap<>();
        for (VodHighlight h : concat(a, b, c)) {
            if (h.getId() != null) seen.putIfAbsent(h.getId(), h);
        }
        List<VodHighlight> result = new ArrayList<>(seen.values());
        return result.size() > totalK ? result.subList(0, totalK) : result;
    }

    @SafeVarargs
    private List<VodHighlight> concat(List<VodHighlight>... lists) {
        List<VodHighlight> all = new ArrayList<>();
        for (List<VodHighlight> list : lists) all.addAll(list);
        return all;
    }

    private VodHighlight mapRow(Readable row) {
        return VodHighlight.builder()
                .id(row.get("id", Long.class))
                .videoNo(row.get("video_no", String.class))
                .startSeconds(row.get("start_seconds", Integer.class))
                .endSeconds(row.get("end_seconds", Integer.class))
                .sceneLabel(row.get("scene_label", String.class))
                .category(row.get("category", String.class))
                .reasonSummary(row.get("reason_summary", String.class))
                .emotionDominance(row.get("emotion_dominance", String.class))
                .densityRatio(row.get("density_ratio", Double.class))
                .hypeRatio(row.get("hype_ratio", Double.class))
                .laughRatio(row.get("laugh_ratio", Double.class))
                .surpriseRatio(row.get("surprise_ratio", Double.class))
                .tensionRatio(row.get("tension_ratio", Double.class))
                .keywordSummary(row.get("keyword_summary", String.class))
                .highlightScore(row.get("highlight_score", Double.class))
                .build();
    }

    private String safeCategory(String category) {
        return (category == null || category.isBlank()) ? "UNKNOWN" : category;
    }

    private String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
