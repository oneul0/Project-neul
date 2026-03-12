package com.neul.collector.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * application.yaml의 chzzk.api.* 설정을 바인딩하는 클래스.
 * clientId, clientSecret은 반드시 환경변수(CHZZK_CLIENT_ID, CHZZK_CLIENT_SECRET)로 주입.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chzzk.api")
public class ChzzkProperties {
    private String baseUrl;
    private String authUrl;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
}
