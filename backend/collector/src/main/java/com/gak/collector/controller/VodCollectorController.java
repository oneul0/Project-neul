package com.gak.collector.controller;

import com.gak.collector.service.VodAnalysisStatusReportingService;
import com.gak.collector.service.VodChatCrawlerService;
import com.gak.collector.service.VodCrawlDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * VOD 수집/상태 HTTP 어댑터.
 * HTTP adapter: request validation → service call → HTTP mapping. No business logic.
 */
@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
@Slf4j
public class VodCollectorController {

    private final VodChatCrawlerService vodChatCrawlerService;
    private final VodAnalysisStatusReportingService statusReportingService;
    private final VodCrawlDispatchService crawlDispatchService;

    @GetMapping("/{videoNo}/metadata")
    public Mono<VodMetadataResponse> getMetadata(@PathVariable String videoNo) {
        return vodChatCrawlerService.fetchVideoMetadata(videoNo);
    }

    @GetMapping("/{videoNo}/status")
    public Mono<VodAnalysisStatusResponse> getStatus(@PathVariable String videoNo) {
        return statusReportingService.getStatusWithFallback(videoNo);
    }

    @PostMapping("/{videoNo}/crawl")
    public Mono<String> triggerCrawl(@PathVariable String videoNo) {
        return crawlDispatchService.dispatch(videoNo);
    }
}
