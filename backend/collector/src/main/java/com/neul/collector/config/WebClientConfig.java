package com.neul.collector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Chzzk Open API용 WebClient Bean 설정.
 * baseUrl은 ChzzkProperties에서 읽어오며, 요청별 Authorization 헤더는 각 메서드에서 추가.
 * Content-Type: application/json 은 공식 API Tips에 따라 기본 헤더로 설정.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient chzzkWebClient(ChzzkProperties props) {
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
