package com.neul.collector.controller;

import com.neul.collector.service.VodChatCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
public class VodCollectorController {

    private final VodChatCrawlerService vodChatCrawlerService;

    @PostMapping("/{videoNo}/crawl")
    public Mono<String> triggerCrawl(@PathVariable String videoNo) {
        // 비동기로 실행하고 즉시 응답 반환 (Fire and forget style for MVP)
        vodChatCrawlerService.crawlFullVodChat(videoNo)
                .subscribe(); 
        return Mono.just("Crawl started for videoNo: " + videoNo);
    }
}
