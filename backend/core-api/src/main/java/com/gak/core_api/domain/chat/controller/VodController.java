package com.gak.core_api.domain.chat.controller;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import com.gak.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.gak.core_api.domain.chat.repository.VodHighlightRepository;
import com.gak.core_api.domain.chat.repository.VodTimelinePointRepository;
import com.gak.core_api.domain.chat.service.OwnerIdentityResolver;
import com.gak.core_api.domain.chat.service.UserVodLibraryService;
import com.gak.core_api.domain.chat.service.VodAnalysisSlotService;
import com.gak.core_api.domain.chat.service.VodAnalysisSlotService.SlotResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
@Slf4j
public class VodController {

    private final VodHighlightRepository vodHighlightRepository;
    private final VodTimelinePointRepository vodTimelinePointRepository;
    private final OwnerIdentityResolver ownerIdentityResolver;
    private final UserVodLibraryService userVodLibraryService;
    private final VodAnalysisSlotService slotService;
    private final WebClient collectorWebClient = WebClient.builder()
            .baseUrl("http://localhost:8081")
            .build();

    @GetMapping("/{videoNo}/highlights")
    public Flux<VodHighlight> getHighlights(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);
        return userVodLibraryService.getPersonalizedHighlights(ownerId, videoNo)
                .collectList()
                .flatMapMany(highlights -> syncOwnerLibrary(
                                ownerId,
                                videoNo,
                                !highlights.isEmpty() ? "READY" : "VIEWED",
                                !highlights.isEmpty()
                        )
                        .thenMany(Flux.fromIterable(highlights)))
                .onErrorResume(error -> {
                    log.warn("[VodController] Failed to personalize highlights for videoNo={}, falling back to default order", videoNo, error);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo);
                });
    }

    @GetMapping("/{videoNo}/timeline")
    public Flux<VodTimelinePointEntity> getTimeline(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);
        return syncOwnerLibrary(ownerId, videoNo, "VIEWED", false)
                .thenMany(vodTimelinePointRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo))
                .collectList()
                .flatMapMany(points -> {
                    if (!points.isEmpty()) {
                        return Flux.fromIterable(points);
                    }

                    log.info("[VodController] Timeline is empty for videoNo={}, building fallback from highlights", videoNo);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo)
                            .map(this::toFallbackTimelinePoint);
                })
                .onErrorResume(error -> {
                    log.warn("[VodController] Failed to load timeline for videoNo={}, returning highlight-based fallback", videoNo, error);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo)
                            .map(this::toFallbackTimelinePoint)
                            .onErrorResume(fallbackError -> {
                                log.warn("[VodController] Failed to build fallback timeline for videoNo={}, returning empty timeline", videoNo, fallbackError);
                                return Flux.empty();
                            });
                });
    }

    /**
     * VOD 분석 시작. 슬롯 가드레일로 사용자/시스템 동시 분석 수를 제한.
     *
     * - REJECTED_USER(429): 해당 사용자가 이미 분석 중
     * - REJECTED_GLOBAL(503): 시스템 전체 분석 슬롯 소진
     * - ACQUIRED: 분석 파이프라인 시작, 슬롯은 완료/실패 Kafka 이벤트 수신 시 자동 반납
     */
    @PostMapping("/{videoNo}/analyze")
    public Mono<ResponseEntity<String>> triggerAnalysis(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);

        return slotService.tryAcquire(ownerId, videoNo)
                .flatMap(result -> switch (result) {
                    case REJECTED_USER -> {
                        log.info("[VodController] Analysis rejected (user limit): ownerId={}, videoNo={}", ownerId, videoNo);
                        yield Mono.just(ResponseEntity
                                .status(HttpStatus.TOO_MANY_REQUESTS)
                                .<String>body("이미 분석 중인 VOD가 있습니다. 완료 후 다시 시도해주세요."));
                    }
                    case REJECTED_GLOBAL -> {
                        log.info("[VodController] Analysis rejected (global limit): ownerId={}, videoNo={}", ownerId, videoNo);
                        yield Mono.just(ResponseEntity
                                .status(HttpStatus.SERVICE_UNAVAILABLE)
                                .<String>body("현재 분석 요청이 많습니다. 잠시 후 다시 시도해주세요."));
                    }
                    case ACQUIRED -> doTriggerAnalysis(videoNo, ownerId)
                            .map(ResponseEntity::ok)
                            .onErrorResume(e -> {
                                log.error("[VodController] Analysis trigger failed, releasing slot: videoNo={}", videoNo, e);
                                return slotService.releaseByVideoNo(videoNo)
                                        .then(Mono.just(ResponseEntity
                                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                .<String>body("분석 요청 처리 중 오류가 발생했습니다.")));
                            });
                });
    }

    private Mono<String> doTriggerAnalysis(String videoNo, String ownerId) {
        return vodHighlightRepository.deleteAllByVideoNo(videoNo)
                .onErrorResume(error -> {
                    log.warn("[VodController] Failed to clear existing highlights for videoNo={}, continuing anyway", videoNo, error);
                    return Mono.empty();
                })
                .then(vodTimelinePointRepository.deleteAllByVideoNo(videoNo))
                .onErrorResume(error -> {
                    log.warn("[VodController] Failed to clear timeline points for videoNo={}, continuing anyway", videoNo, error);
                    return Mono.empty();
                })
                .then(
                        collectorWebClient.post()
                                .uri("/api/v1/vod/" + videoNo + "/crawl")
                                .retrieve()
                                .bodyToMono(String.class)
                )
                .flatMap(response -> syncOwnerLibrary(ownerId, videoNo, "ANALYZING", false)
                        .thenReturn("VOD analysis request sent: " + response))
                .doOnError(error -> log.error("[VodController] Failed to trigger analysis for videoNo={}", videoNo, error));
    }

    private Mono<Void> syncOwnerLibrary(
            String ownerId,
            String videoNo,
            String status,
            boolean analyzed
    ) {
        if (ownerId == null || ownerId.isBlank()) {
            return Mono.empty();
        }

        Mono<?> update = analyzed
                ? userVodLibraryService.markAnalyzed(ownerId, videoNo, status)
                : userVodLibraryService.touchVideo(ownerId, videoNo, status);

        return update
                .doOnError(error -> log.warn(
                        "[VodController] Failed to sync user VOD library ownerId={}, videoNo={}, status={}",
                        ownerId,
                        videoNo,
                        status,
                        error
                ))
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    private VodTimelinePointEntity toFallbackTimelinePoint(VodHighlight highlight) {
        double score = highlight.getHighlightScore() != null ? highlight.getHighlightScore() : 0.0d;
        int participantCount = Math.max(1, (int) Math.round(score));
        int messageCount = Math.max(participantCount, (int) Math.round(score * 2));

        return VodTimelinePointEntity.builder()
                .id(highlight.getId())
                .videoNo(highlight.getVideoNo())
                .startSeconds(highlight.getStartSeconds())
                .endSeconds(highlight.getEndSeconds())
                .messageCount(messageCount)
                .participantCount(participantCount)
                .activityScore(score)
                .category(highlight.getCategory())
                .topMessage(highlight.getTopMessage())
                .build();
    }
}
