package com.neul.core_api.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodHighlightPoint;
import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * vod-analyzed-topic을 소비하여 DB에 저장하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VodHighlightConsumer {

    private final VodHighlightRepository vodHighlightRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "vod-analyzed-topic", groupId = "neul-core-api-vod-group")
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
                    .description(point.getDescription())
                    .reasonSummary(point.getReasonSummary())
                    .topMessage(point.getTopMessage())
                    .build();

            vodHighlightRepository.save(entity)
                    .subscribe(saved -> log.debug("[VOD-Highlight-Consumer] Saved highlight unit: videoNo={}, time={}s", 
                            saved.getVideoNo(), saved.getStartSeconds()));

        } catch (Exception e) {
            log.error("[VOD-Highlight-Consumer] Failed to handle VOD highlight point", e);
        }
    }
}
