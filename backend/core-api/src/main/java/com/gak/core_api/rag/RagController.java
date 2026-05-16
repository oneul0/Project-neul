package com.gak.core_api.rag;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * analyzer 서비스가 LLM 호출 전 few-shot 예시를 조회하기 위한 내부 엔드포인트.
 * 외부 노출 없이 마이크로서비스 간 통신용으로만 사용한다.
 */
@RestController
@RequestMapping("/internal/rag")
@RequiredArgsConstructor
public class RagController {

    private final HighlightRetrievalService retrievalService;
    private final HighlightEmbeddingService embeddingService;

    /**
     * 임베딩 텍스트를 받아 유사 하이라이트 few-shot 문자열을 반환한다.
     * analyzer가 analyzeHighlight() 호출 직전에 사용한다.
     */
    @PostMapping("/few-shot")
    public Mono<String> getFewShot(
            @RequestBody FewShotRequest request,
            @RequestParam(defaultValue = "3") int k) {

        VodHighlight candidate = VodHighlight.builder()
                .videoNo(request.videoNo())
                .category(request.category())
                .sceneLabel(request.sceneLabel())
                .emotionDominance(request.emotionDominance())
                .densityRatio(request.densityRatio())
                .uniqueUserRatio(request.uniqueUserRatio())
                .hypeRatio(request.hypeRatio())
                .laughRatio(request.laughRatio())
                .surpriseRatio(request.surpriseRatio())
                .tensionRatio(request.tensionRatio())
                .keywordSummary(request.keywordSummary())
                .build();

        return retrievalService.retrieve(candidate, k)
                .map(this::formatFewShot);
    }

    private String formatFewShot(List<VodHighlight> examples) {
        if (examples.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("[유사 사례]\n");
        for (int i = 0; i < examples.size(); i++) {
            VodHighlight h = examples.get(i);
            sb.append(String.format(
                    "사례%d: [%s] %s — dominant=%s density=%.1fx\n→ scene_label: \"%s\", reasoning: \"%s\"\n",
                    i + 1,
                    safe(h.getSceneLabel()),
                    safe(h.getCategory()),
                    safe(h.getEmotionDominance()),
                    nullToZero(h.getDensityRatio()),
                    safe(h.getSceneLabel()),
                    truncate(h.getReasonSummary(), 60)
            ));
        }
        return sb.toString().trim();
    }

    private String safe(String v) { return v == null ? "unknown" : v; }
    private double nullToZero(Double v) { return v == null ? 0.0 : v; }
    private String truncate(String v, int max) {
        if (v == null) return "";
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    public record FewShotRequest(
            String videoNo,
            String category,
            String sceneLabel,
            String emotionDominance,
            Double densityRatio,
            Double uniqueUserRatio,
            Double hypeRatio,
            Double laughRatio,
            Double surpriseRatio,
            Double tensionRatio,
            String keywordSummary
    ) {}
}
