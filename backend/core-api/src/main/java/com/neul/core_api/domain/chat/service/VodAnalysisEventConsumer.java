package com.neul.core_api.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodAnalysisCompletedEvent;
import com.neul.common.dto.VodAnalysisFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * VOD 분석 완료/실패 이벤트를 수신해 슬롯을 반납하는 컨슈머.
 *
 * VodController.triggerAnalysis() 에서 슬롯을 획득하고,
 * 분석 파이프라인이 끝나는 시점(complete/failed)에 여기서 반납.
 * 슬롯이 반납돼야 다음 분석 요청이 수락된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodAnalysisEventConsumer {

    private final VodAnalysisSlotService slotService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "vod-analysis-complete-topic", groupId = "neul-core-api-vod-status-group")
    public void onAnalysisCompleted(String json) {
        try {
            VodAnalysisCompletedEvent event = objectMapper.readValue(json, VodAnalysisCompletedEvent.class);
            log.info("[VodEventConsumer] Analysis completed: videoNo={}, highlights={}, timeline={}",
                    event.getVideoNo(), event.getHighlightsCount(), event.getTimelinePointsCount());

            slotService.releaseByVideoNo(event.getVideoNo())
                    .subscribe(
                            null,
                            e -> log.warn("[VodEventConsumer] Slot release failed on completion: videoNo={}", event.getVideoNo(), e)
                    );
        } catch (Exception e) {
            log.error("[VodEventConsumer] Failed to process analysis completed event", e);
        }
    }

    @KafkaListener(topics = "vod-analysis-failed-topic", groupId = "neul-core-api-vod-status-group")
    public void onAnalysisFailed(String json) {
        try {
            VodAnalysisFailedEvent event = objectMapper.readValue(json, VodAnalysisFailedEvent.class);
            log.warn("[VodEventConsumer] Analysis failed: videoNo={}, reason={}", event.getVideoNo(), event.getReason());

            slotService.releaseByVideoNo(event.getVideoNo())
                    .subscribe(
                            null,
                            e -> log.warn("[VodEventConsumer] Slot release failed on failure: videoNo={}", event.getVideoNo(), e)
                    );
        } catch (Exception e) {
            log.error("[VodEventConsumer] Failed to process analysis failed event", e);
        }
    }
}
