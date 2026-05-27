package com.gak.collector.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * core-api 모듈로의 HTTP 요청을 캡슐화하는 게이트웨이.
 * VodCollectorController / VodAnalysisStatusReportingService 에서
 * 직접 WebClient를 사용하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreApiGateway {

    // @Bean("coreApiWebClient") — 빈 이름이 필드명과 일치하므로 자동 주입
    private final WebClient coreApiWebClient;

    /**
     * core-api에서 VOD 하이라이트가 1건 이상 존재하는지 확인한다.
     * GET /api/v1/vod/{videoNo}/highlights
     */
    public Mono<Boolean> hasHighlights(String videoNo) {
        return coreApiWebClient.get()
                .uri("/api/v1/vod/{videoNo}/highlights", videoNo)
                .retrieve()
                .bodyToFlux(Object.class)
                .take(1)
                .hasElements()
                .doOnError(error ->
                        log.warn("[CoreApiGateway] Failed to check highlights for videoNo={}", videoNo, error));
    }
}
