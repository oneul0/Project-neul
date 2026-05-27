package com.gak.collector.service;

import com.gak.collector.controller.VodAnalysisStatusResponse;
import com.gak.collector.gateway.CoreApiGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * VOD 분석 상태 조회 서비스.
 *
 * <p>ANALYZING/IDLE 상태일 때 core-api에서 하이라이트 존재 여부를 확인해 상태를 보정한다.
 * <ul>
 *   <li>highlights 존재 → markCompleted
 *   <li>ANALYZING + stale(30분) + highlights 없음 → markFailed
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodAnalysisStatusReportingService {

    /** stale ANALYZING 판정 기준 — VodCollectorController에서 이관. */
    private static final Duration STALE_ANALYZING_TIMEOUT = Duration.ofMinutes(30);

    private final VodAnalysisStatusService vodAnalysisStatusService;
    private final CoreApiGateway coreApiGateway;

    public Mono<VodAnalysisStatusResponse> getStatusWithFallback(String videoNo) {
        VodAnalysisStatusResponse current = vodAnalysisStatusService.getStatus(videoNo);

        if ("ANALYZING".equals(current.status())) {
            boolean timedOut = current.startedAt() != null
                    && Duration.between(current.startedAt(), Instant.now())
                               .compareTo(STALE_ANALYZING_TIMEOUT) >= 0;
            return checkHighlightsFallback(videoNo, current, timedOut);
        }

        if ("IDLE".equals(current.status())) {
            return checkHighlightsFallback(videoNo, current, false);
        }

        return Mono.just(current);
    }

    private Mono<VodAnalysisStatusResponse> checkHighlightsFallback(
            String videoNo, VodAnalysisStatusResponse current, boolean markFailedIfEmpty) {
        return coreApiGateway.hasHighlights(videoNo)
                .map(hasHighlights -> {
                    if (hasHighlights) {
                        vodAnalysisStatusService.markCompleted(
                                videoNo, current.pagesProcessed(), current.chatsCollected());
                        log.info("[VodAnalysisStatusReportingService] Marking videoNo={} as completed via highlight fallback (was {})",
                                videoNo, current.status());
                    } else if (markFailedIfEmpty) {
                        vodAnalysisStatusService.markFailed(
                                videoNo, "분석 시간이 초과되었습니다. 다시 시도해주세요.");
                        log.warn("[VodAnalysisStatusReportingService] Marking videoNo={} as failed (stale ANALYZING timeout)", videoNo);
                    }
                    return vodAnalysisStatusService.getStatus(videoNo);
                })
                .onErrorResume(error -> {
                    log.warn("[VodAnalysisStatusReportingService] Failed to check highlight fallback for videoNo={}", videoNo, error);
                    return Mono.just(current);
                });
    }
}
