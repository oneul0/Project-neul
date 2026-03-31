package com.neul.core_api.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodTimelinePoint;
import com.neul.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.neul.core_api.domain.chat.repository.VodTimelinePointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VodTimelinePointConsumer {

    private final VodTimelinePointRepository vodTimelinePointRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "vod-window-summary-topic", groupId = "neul-core-api-vod-timeline-group")
    public void consumeTimelinePoint(String json) {
        try {
            VodTimelinePoint point = objectMapper.readValue(json, VodTimelinePoint.class);

            VodTimelinePointEntity entity = VodTimelinePointEntity.builder()
                    .videoNo(point.getVideoNo())
                    .startSeconds(point.getStartSeconds())
                    .endSeconds(point.getEndSeconds())
                    .messageCount(point.getMessageCount())
                    .participantCount(point.getParticipantCount())
                    .activityScore(point.getActivityScore())
                    .category(point.getCategory())
                    .topMessage(point.getTopMessage())
                    .build();

            vodTimelinePointRepository.save(entity)
                    .subscribe(saved -> log.debug(
                            "[VOD-Timeline-Consumer] Saved timeline point: videoNo={}, time={}s",
                            saved.getVideoNo(),
                            saved.getStartSeconds()
                    ));
        } catch (Exception e) {
            log.error("[VOD-Timeline-Consumer] Failed to handle timeline point", e);
        }
    }
}
