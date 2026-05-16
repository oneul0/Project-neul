package com.gak.core_api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chzzk Open API 설정 (core-api용).
 * 환경변수 CHZZK_CLIENT_ID, CHZZK_CLIENT_SECRET으로 주입.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chzzk.api")
public class ChzzkProperties {
    private String baseUrl;
    private String clientId;
    private String clientSecret;
}
