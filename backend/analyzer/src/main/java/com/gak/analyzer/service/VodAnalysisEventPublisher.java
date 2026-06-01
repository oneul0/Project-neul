package com.gak.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.VodAnalysisCompletedEvent;
import com.gak.common.dto.VodAnalysisFailedEvent;
import com.gak.common.dto.VodCrawlCompletedEvent;
import com.gak.common.dto.VodHighlightPoint;
import com.gak.common.dto.VodTimelinePoint;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VodAnalysisEventPublisher {

    private static final String TIMELINE_TOPIC = "vod-window-summary-topic";
    private static final String HIGHLIGHT_TOPIC = "vod-analyzed-topic";
    private static final String COMPLETION_TOPIC = "vod-analysis-complete-topic";
    private static final String FAILURE_TOPIC = "vod-analysis-failed-topic";

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public void publishTimelinePoint(String videoNo, VodTimelinePoint point) throws Exception {
        kafkaTemplate.send(TIMELINE_TOPIC, videoNo, objectMapper.writeValueAsString(point));
    }

    public void publishHighlightPoint(String videoNo, VodHighlightPoint point) throws Exception {
        kafkaTemplate.send(HIGHLIGHT_TOPIC, videoNo, objectMapper.writeValueAsString(point));
    }

    public void publishCompletion(String videoNo, int timelinePointsCount, int highlightsCount) throws Exception {
        VodAnalysisCompletedEvent event = VodAnalysisCompletedEvent.builder()
                .videoNo(videoNo)
                .timelinePointsCount(timelinePointsCount)
                .highlightsCount(highlightsCount)
                .build();

        kafkaTemplate.send(COMPLETION_TOPIC, videoNo, objectMapper.writeValueAsString(event));
    }

    public void publishFailure(VodCrawlCompletedEvent sourceEvent, String reason) throws Exception {
        VodAnalysisFailedEvent failedEvent = VodAnalysisFailedEvent.builder()
                .videoNo(sourceEvent.getVideoNo())
                .pagesProcessed(sourceEvent.getPagesProcessed())
                .chatsCollected(sourceEvent.getChatsCollected())
                .reason(reason)
                .build();

        kafkaTemplate.send(FAILURE_TOPIC, sourceEvent.getVideoNo(), objectMapper.writeValueAsString(failedEvent));
    }
}
