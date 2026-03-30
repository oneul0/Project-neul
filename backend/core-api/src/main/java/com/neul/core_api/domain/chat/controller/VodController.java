package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import lombok.RequiredArgsConstructor;
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
public class VodController {

    private final VodHighlightRepository vodHighlightRepository;
    private final WebClient collectorWebClient = WebClient.builder().baseUrl("http://localhost:8081").build();

    /**
     * 특정 VOD의 하이라이트 타임라인 조회.
     */
    @GetMapping("/{videoNo}/highlights")
    public Flux<VodHighlight> getHighlights(@PathVariable String videoNo) {
        return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo);
    }

    /**
     * VOD 분석 시작 트리거 (collector 호출).
     */
    @PostMapping("/{videoNo}/analyze")
    public Mono<String> triggerAnalysis(@PathVariable String videoNo) {
        return collectorWebClient.post()
                .uri("/api/v1/vod/" + videoNo + "/crawl")
                .retrieve()
                .bodyToMono(String.class)
                .map(res -> "VOD analysis request sent: " + res);
    }
}
