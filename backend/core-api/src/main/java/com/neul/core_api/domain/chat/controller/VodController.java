package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import com.neul.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.neul.core_api.domain.chat.repository.VodTimelinePointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * VOD 하이라이트 조회 및 분석 트리거 API.
 */
@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
@Slf4j
public class VodController {

    private final VodHighlightRepository vodHighlightRepository;
    private final VodTimelinePointRepository vodTimelinePointRepository;
    private final WebClient collectorWebClient = WebClient.builder().baseUrl("http://localhost:8081").build();

    /**
     * 특정 VOD의 하이라이트 타임라인 조회.
     */
    @GetMapping("/{videoNo}/highlights")
    public Flux<VodHighlight> getHighlights(@PathVariable String videoNo) {
        return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo);
    }

    @GetMapping("/{videoNo}/timeline")
    public Flux<VodTimelinePointEntity> getTimeline(@PathVariable String videoNo) {
        return vodTimelinePointRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo)
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
     * VOD 분석 시작 트리거 (collector 호출).
     */
    @PostMapping("/{videoNo}/analyze")
    public Mono<String> triggerAnalysis(@PathVariable String videoNo) {
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
                .map(res -> "VOD analysis request sent: " + res)
                .doOnError(error -> log.error("[VodController] Failed to trigger analysis for videoNo={}", videoNo, error));
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
