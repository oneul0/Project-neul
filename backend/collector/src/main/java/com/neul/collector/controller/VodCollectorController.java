package com.neul.collector.controller;

import com.neul.collector.service.VodAnalysisStatusService;
import com.neul.collector.service.VodChatCrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class VodCollectorController {

    private final VodChatCrawlerService vodChatCrawlerService;
    private final VodAnalysisStatusService vodAnalysisStatusService;
    private final WebClient coreApiWebClient = WebClient.builder().baseUrl("http://localhost:8083").build();

    @GetMapping("/{videoNo}/metadata")
    public Mono<VodMetadataResponse> getMetadata(@PathVariable String videoNo) {
        return vodChatCrawlerService.fetchVideoMetadata(videoNo);
    }

    @GetMapping("/{videoNo}/status")
    public Mono<VodAnalysisStatusResponse> getStatus(@PathVariable String videoNo) {
        VodAnalysisStatusResponse current = vodAnalysisStatusService.getStatus(videoNo);
        if (!"ANALYZING".equals(current.status())) {
            return Mono.just(current);
        }

        return coreApiWebClient.get()
                .uri("/api/v1/vod/{videoNo}/highlights", videoNo)
                .retrieve()
                .bodyToFlux(Object.class)
                .take(1)
                .hasElements()
                .map(hasHighlights -> {
                    if (hasHighlights) {
                        vodAnalysisStatusService.markCompleted(
                                videoNo,
                                current.pagesProcessed(),
                                current.chatsCollected()
                        );
                        log.info("[VOD-Crawler] Marking videoNo={} as completed from highlight fallback", videoNo);
                        return vodAnalysisStatusService.getStatus(videoNo);
                    }
                    return current;
                })
                .onErrorResume(error -> {
                    log.warn("[VOD-Crawler] Failed to verify fallback completion for videoNo={}", videoNo, error);
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
