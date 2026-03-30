package com.neul.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chzzk Open API용 WebClient Bean 설정.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient chzzkWebClient(WebClient.Builder builder, ChzzkProperties props) {
        builder.baseUrl(props.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        if (props.getNidAut() != null && props.getNidSes() != null) {
            String cookie = String.format("NID_AUT=%s; NID_SES=%s", props.getNidAut(), props.getNidSes());
            builder.defaultHeader("Cookie", cookie);
        }

        return builder.build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
