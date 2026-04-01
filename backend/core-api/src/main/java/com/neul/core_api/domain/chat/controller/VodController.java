package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import com.neul.core_api.domain.chat.repository.VodTimelinePointRepository;
import com.neul.core_api.domain.chat.service.OwnerIdentityResolver;
import com.neul.core_api.domain.chat.service.UserVodLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
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
    private final WebClient collectorWebClient = WebClient.builder()
            .baseUrl("http://localhost:8081")
            .build();

    @GetMapping("/{videoNo}/highlights")
    public Flux<VodHighlight> getHighlights(
            @PathVariable String videoNo,
            ServerHttpRequest request
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(request);
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
            ServerHttpRequest request
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(request);
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

    @PostMapping("/{videoNo}/analyze")
    public Mono<String> triggerAnalysis(
            @PathVariable String videoNo,
            ServerHttpRequest request
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(request);
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
