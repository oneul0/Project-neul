package com.gak.collector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * core-api 모듈과의 HTTP 통신에 사용하는 WebClient Bean.
 * app.core-api.base-url (application.yaml) 로 baseUrl을 주입받는다.
 */
@Configuration
public class CoreApiWebClientConfig {

    @Value("${app.core-api.base-url:http://localhost:8083}")
    private String coreApiBaseUrl;

    @Bean("coreApiWebClient")
    public WebClient coreApiWebClient(WebClient.Builder builder) {
        return builder.baseUrl(coreApiBaseUrl).build();
    }
}
