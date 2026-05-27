package com.gak.core_api.domain.chat.service;

import com.gak.core_api.domain.chat.gateway.CollectorGateway;
import com.gak.core_api.domain.chat.repository.VodHighlightRepository;
import com.gak.core_api.domain.chat.repository.VodTimelinePointRepository;
import com.gak.core_api.domain.chat.service.dto.TriggerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * VOD 분석 트리거 파이프라인 오케스트레이터.
 *
 * <p>슬롯 가드레일 → 기존 데이터 DELETE → collector 크롤 요청 → 라이브러리 sync
 * 의 4단계 워크플로우를 캡슐화한다.
 *
 * <p>실패 시 슬롯 보상 트랜잭션(releaseByVideoNo)을 onErrorResume에서 수행한다.
 * 정상 ACQUIRED 흐름에서는 슬롯을 해제하지 않는다 (Kafka 컨슈머 책임).
 *
 * <p>TODO: 확인 필요 — R2DBC 트랜잭션 도입 검토 (두 DELETE의 부분 실패 처리).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodAnalysisOrchestrator {

    private final VodHighlightRepository vodHighlightRepository;
    private final VodTimelinePointRepository vodTimelinePointRepository;
    private final VodAnalysisSlotService slotService;
    private final CollectorGateway collectorGateway;
    private final UserVodLibraryService userVodLibraryService;

    public Mono<TriggerResult> trigger(String videoNo, String ownerId) {
        return slotService.tryAcquire(ownerId, videoNo)
                .flatMap(result -> switch (result) {
                    case REJECTED_USER -> {
                        log.info("[VodAnalysisOrchestrator] Slot rejected (user limit): ownerId={}, videoNo={}", ownerId, videoNo);
                        yield Mono.just(new TriggerResult.RejectedUser());
                    }
                    case REJECTED_GLOBAL -> {
                        log.info("[VodAnalysisOrchestrator] Slot rejected (global limit): ownerId={}, videoNo={}", ownerId, videoNo);
                        yield Mono.just(new TriggerResult.RejectedGlobal());
                    }
                    case ACQUIRED -> runPipeline(videoNo, ownerId)
                            .<TriggerResult>map(TriggerResult.Accepted::new)
                            .onErrorResume(e -> {
                                log.error("[VodAnalysisOrchestrator] Pipeline failed, releasing slot: videoNo={}", videoNo, e);
                                // 정상 흐름에서 release하지 않음 — 에러 시에만 보상
                                return slotService.releaseByVideoNo(videoNo)
                                        .then(Mono.just(new TriggerResult.Failed(e.getMessage())));
                            });
                });
    }

    private Mono<String> runPipeline(String videoNo, String ownerId) {
        return vodHighlightRepository.deleteAllByVideoNo(videoNo)
                .then()
                .onErrorResume(error -> {
                    log.warn("[VodAnalysisOrchestrator] Failed to clear highlights for videoNo={}, continuing", videoNo, error);
                    return Mono.empty();
                })
                .then(vodTimelinePointRepository.deleteAllByVideoNo(videoNo).then())
                .onErrorResume(error -> {
                    log.warn("[VodAnalysisOrchestrator] Failed to clear timeline points for videoNo={}, continuing", videoNo, error);
                    return Mono.empty();
                })
                .then(collectorGateway.triggerCrawl(videoNo))
                .flatMap(response ->
                        userVodLibraryService.syncStatus(ownerId, videoNo, "ANALYZING", false)
                                .thenReturn("VOD analysis request sent: " + response))
                .doOnError(error ->
                        log.error("[VodAnalysisOrchestrator] Failed to trigger analysis for videoNo={}", videoNo, error));
    }
}
