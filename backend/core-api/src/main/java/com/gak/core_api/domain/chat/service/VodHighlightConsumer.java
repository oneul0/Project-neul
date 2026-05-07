package com.gak.core_api.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.VodHighlightPoint;
import com.gak.core_api.domain.chat.entity.VodHighlight;
import com.gak.core_api.domain.chat.repository.VodHighlightRepository;
import com.gak.core_api.rag.HighlightEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * vod-analyzed-topic을 소비하여 DB에 저장하는 서비스.
 * 저장 후 RAG 임베딩 생성을 비동기로 트리거한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VodHighlightConsumer {

    private final VodHighlightRepository vodHighlightRepository;
    private final HighlightEmbeddingService highlightEmbeddingService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "vod-analyzed-topic", groupId = "gak-core-api-vod-group")
    public void consumeAnalyzedVodHighlight(String json) {
        try {
            VodHighlightPoint point = objectMapper.readValue(json, VodHighlightPoint.class);

            VodHighlight entity = VodHighlight.builder()
                    .videoNo(point.getVideoNo())
                    .startSeconds(point.getStartSeconds())
                    .endSeconds(point.getEndSeconds())
                    .highlightScore(point.getHighlightScore())
                    .intensityScore(point.getIntensityScore())
                    .transitionScore(point.getTransitionScore())
                    .editabilityScore(point.getEditabilityScore())
                    .category(point.getCategory())
                    .reactionLabel(point.getReactionLabel())
                    .sceneLabel(point.getSceneLabel())
                    .description(point.getDescription())
                    .reasonSummary(point.getReasonSummary())
                    .topMessage(point.getTopMessage())
                    .laughRatio(point.getLaughRatio())
                    .hypeRatio(point.getHypeRatio())
                    .surpriseRatio(point.getSurpriseRatio())
                    .tensionRatio(point.getTensionRatio())
                    .densityRatio(point.getDensityRatio())
                    .uniqueUserRatio(point.getUniqueUserRatio())
                    .emotionDominance(point.getEmotionDominance())
                    .keywordSummary(point.getKeywordSummary())
                    .build();

            vodHighlightRepository.save(entity)
                    .flatMap(saved -> highlightEmbeddingService.embedAndStore(saved))
                    .subscribe(
                            saved -> log.debug("[VOD-Highlight-Consumer] Saved+embedded: videoNo={}, time={}s",
                                    saved.getVideoNo(), saved.getStartSeconds()),
                            err -> log.error("[VOD-Highlight-Consumer] Failed to save or embed highlight", err)
                    );

        } catch (Exception e) {
            log.error("[VOD-Highlight-Consumer] Failed to handle VOD highlight point", e);
        }
    }
}
