package com.gak.core_api.domain.chat.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * collector 모듈로의 HTTP 요청을 캡슐화하는 게이트웨이.
 * VodController / VodAnalysisOrchestrator 에서 직접 WebClient를 사용하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectorGateway {

    // @Bean("collectorWebClient") — 빈 이름이 필드명과 일치하므로 자동 주입
    private final WebClient collectorWebClient;

    /**
     * collector 모듈에 VOD 크롤 시작을 요청한다.
     * POST /api/v1/vod/{videoNo}/crawl
     */
    public Mono<String> triggerCrawl(String videoNo) {
        return collectorWebClient.post()
                .uri("/api/v1/vod/{videoNo}/crawl", videoNo)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(error ->
                        log.error("[CollectorGateway] Failed to trigger crawl for videoNo={}", videoNo, error));
    }
}
