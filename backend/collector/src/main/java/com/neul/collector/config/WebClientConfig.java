package com.neul.collector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chzzk Open API용 WebClient Bean 설정.
 * baseUrl은 ChzzkProperties에서 읽어오며, 요청별 Authorization 헤더는 각 메서드에서 추가.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient chzzkWebClient(ChzzkProperties props) {
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
