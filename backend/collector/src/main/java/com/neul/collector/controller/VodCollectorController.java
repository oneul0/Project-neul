package com.neul.collector.controller;

import com.neul.collector.service.VodAnalysisStatusService;
import com.neul.collector.service.VodChatCrawlerService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
@Slf4j
public class VodCollectorController {

    private final VodChatCrawlerService vodChatCrawlerService;
    private final VodAnalysisStatusService vodAnalysisStatusService;
    private final WebClient coreApiWebClient = WebClient.builder().baseUrl("http://localhost:8083").build();

    @GetMapping("/{videoNo}/metadata")
    public Mono<VodMetadataResponse> getMetadata(@PathVariable String videoNo) {
        return vodChatCrawlerService.fetchVideoMetadata(videoNo);
    }

    private static final Duration STALE_ANALYZING_TIMEOUT = Duration.ofMinutes(30);

    @GetMapping("/{videoNo}/status")
    public Mono<VodAnalysisStatusResponse> getStatus(@PathVariable String videoNo) {
        VodAnalysisStatusResponse current = vodAnalysisStatusService.getStatus(videoNo);

        if ("ANALYZING".equals(current.status())) {
            boolean timedOut = current.startedAt() != null
                    && Duration.between(current.startedAt(), Instant.now()).compareTo(STALE_ANALYZING_TIMEOUT) >= 0;
            return checkHighlightsFallback(videoNo, current, timedOut);
        }

        if ("IDLE".equals(current.status())) {
            return checkHighlightsFallback(videoNo, current, false);
        }

        return Mono.just(current);
    }

    private Mono<VodAnalysisStatusResponse> checkHighlightsFallback(
            String videoNo, VodAnalysisStatusResponse current, boolean markFailedIfEmpty) {
        return coreApiWebClient.get()
                .uri("/api/v1/vod/{videoNo}/highlights", videoNo)
                .retrieve()
                .bodyToFlux(Object.class)
                .take(1)
                .hasElements()
                .map(hasHighlights -> {
                    if (hasHighlights) {
                        vodAnalysisStatusService.markCompleted(
                                videoNo, current.pagesProcessed(), current.chatsCollected());
                        log.info("[VOD-Crawler] Marking videoNo={} as completed via highlight fallback (was {})",
                                videoNo, current.status());
                    } else if (markFailedIfEmpty) {
                        vodAnalysisStatusService.markFailed(
                                videoNo, "분석 시간이 초과되었습니다. 다시 시도해주세요.");
                        log.warn("[VOD-Crawler] Marking videoNo={} as failed (stale ANALYZING timeout)", videoNo);
                    }
                    return vodAnalysisStatusService.getStatus(videoNo);
                })
                .onErrorResume(error -> {
                    log.warn("[VOD-Crawler] Failed to check highlight fallback for videoNo={}", videoNo, error);
                    return Mono.just(current);
                });
    }

    @PostMapping("/{videoNo}/crawl")
    public Mono<String> triggerCrawl(@PathVariable String videoNo) {
        if (vodAnalysisStatusService.isProcessing(videoNo)) {
            log.info("[VOD-Crawler] Skip duplicate crawl request for videoNo={}", videoNo);
            return Mono.just("Analysis is already running for videoNo: " + videoNo);
        }

        log.info("[VOD-Crawler] Accepted crawl request for videoNo={}", videoNo);
        vodAnalysisStatusService.markRequested(videoNo);
        vodChatCrawlerService.crawlFullVodChat(videoNo)
                .doOnSuccess(progress -> {
                    log.info("[VOD-Crawler] Finished collection for videoNo={}, pages={}, chats={}",
                            videoNo, progress.pagesProcessed(), progress.chatsCollected());
                    vodAnalysisStatusService.markAnalyzing(
                            videoNo,
                            progress.pagesProcessed(),
                            progress.chatsCollected()
                    );
                })
                .doOnError(error -> vodAnalysisStatusService.markFailed(videoNo, error.getMessage()))
                .subscribe();

        return Mono.just("Crawl started for videoNo: " + videoNo);
    }
}
