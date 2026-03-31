package com.neul.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodAnalysisCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VodAnalysisCompletionConsumer {

    private final ObjectMapper objectMapper;
    private final VodAnalysisStatusService vodAnalysisStatusService;

    @KafkaListener(topics = "vod-analysis-complete-topic", groupId = "neul-collector-vod-complete-group")
    public void consumeCompletion(String json) {
        try {
            VodAnalysisCompletedEvent event = objectMapper.readValue(json, VodAnalysisCompletedEvent.class);
            var current = vodAnalysisStatusService.getStatus(event.getVideoNo());
            vodAnalysisStatusService.markCompleted(
                    event.getVideoNo(),
                    current.pagesProcessed(),
                    current.chatsCollected()
            );
            log.info(
                    "[VOD-Crawler] Analysis completed for videoNo={}, timelinePoints={}, highlights={}",
                    event.getVideoNo(),
                    event.getTimelinePointsCount(),
                    event.getHighlightsCount()
            );
        } catch (Exception e) {
            log.error("[VOD-Crawler] Failed to consume analysis completion event", e);
        }
    }
}
