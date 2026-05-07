package com.gak.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.VodAnalysisFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VodAnalysisFailureConsumer {

    private final ObjectMapper objectMapper;
    private final VodAnalysisStatusService vodAnalysisStatusService;

    @KafkaListener(topics = "vod-analysis-failed-topic", groupId = "gak-collector-vod-failed-group")
    public void consumeFailure(String json) {
        try {
            VodAnalysisFailedEvent event = objectMapper.readValue(json, VodAnalysisFailedEvent.class);
            vodAnalysisStatusService.markFailed(event.getVideoNo(), event.getReason());
            log.warn(
                    "[VOD-Crawler] Analysis failed for videoNo={}, pages={}, chats={}, reason={}",
                    event.getVideoNo(),
                    event.getPagesProcessed(),
                    event.getChatsCollected(),
                    event.getReason()
            );
        } catch (Exception e) {
            log.error("[VOD-Crawler] Failed to consume analysis failure event", e);
        }
    }
}
