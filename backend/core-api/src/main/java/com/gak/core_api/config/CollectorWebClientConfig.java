package com.gak.core_api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * collector 모듈과의 HTTP 통신에 사용하는 WebClient Bean.
 * app.collector.base-url (application.yaml) 로 baseUrl을 주입받는다.
 */
@Configuration
public class CollectorWebClientConfig {

    @Value("${app.collector.base-url:http://localhost:8081}")
    private String collectorBaseUrl;

    @Bean("collectorWebClient")
    public WebClient collectorWebClient(WebClient.Builder builder) {
        return builder.baseUrl(collectorBaseUrl).build();
    }
}
