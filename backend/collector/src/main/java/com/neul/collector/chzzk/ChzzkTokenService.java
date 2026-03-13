package com.neul.collector.chzzk;

import com.neul.collector.config.ChzzkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chzzk Access Token 관리 서비스.
 * - 최초 발급(code) 및 갱신(refreshToken) 담당
 * - 메모리에 토큰 저장 (운영 환경에서는 Redis 등 저장소 권장)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkTokenService {

    private final ChzzkProperties props;
    private final WebClient chzzkWebClient;

    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<String> refreshToken = new AtomicReference<>();
    private final AtomicReference<Instant> expiresAt = new AtomicReference<>(Instant.MIN);

    /**
     * 유효한 Access Token을 반환. 만료되었다면 자동으로 갱신 시도.
     */
    public Mono<String> getValidAccessToken() {
        if (hasValidToken()) {
            return Mono.just(accessToken.get());
        }

        if (refreshToken.get() != null) {
            log.info("[Chzzk] Token expired. Attempting refresh...");
            return refreshAccessToken();
        }

        return Mono.error(new ChzzkApiException("No refresh token available. Please log in first."));
    }

    /**
     * 초기 인증 코드를 이용해 첫 토큰 세트 발급.
     */
    public Mono<String> fetchFirstToken(String code, String state) {
        return chzzkWebClient.post()
                .uri("/auth/v1/token")
                .bodyValue(Map.of(
                        "grantType", "authorization_code",
                        "clientId", props.getClientId(),
                        "clientSecret", props.getClientSecret(),
                        "code", code,
                        "state", state
                ))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .flatMap(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    if (content == null) return Mono.error(new ChzzkApiException("Token response has no content"));

                    String access = (String) content.get("accessToken");
                    String refresh = (String) content.get("refreshToken");
                    Object expiresInObj = content.get("expiresIn");
                    long expiresIn = parseLong(expiresInObj);

                    storeTokens(access, refresh, expiresIn);
                    log.info("[Chzzk] First token issued successfully.");
                    return Mono.just(access);
                })
                .onErrorMap(e -> new ChzzkApiException("Failed to issue first token: " + e.getMessage(), e));
    }

    /**
     * Refresh Token을 이용해 만료된 Access Token 갱신.
     */
    private Mono<String> refreshAccessToken() {
        String rfToken = refreshToken.get();
        if (rfToken == null) return Mono.error(new ChzzkApiException("No refresh token available"));

        return chzzkWebClient.post()
                .uri("/auth/v1/token")
                .bodyValue(Map.of(
                        "grantType", "refresh_token",
                        "refreshToken", rfToken,
                        "clientId", props.getClientId(),
                        "clientSecret", props.getClientSecret()
                ))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .flatMap(response -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    if (content == null) return Mono.error(new ChzzkApiException("Token refresh response has no content"));

                    String access = (String) content.get("accessToken");
                    String refresh = (String) content.get("refreshToken"); // 일회용일 수 있으므로 항상 갱신
                    Object expiresInObj = content.get("expiresIn");
                    long expiresIn = parseLong(expiresInObj);

                    storeTokens(access, refresh, expiresIn);
                    log.info("[Chzzk] Token refreshed successfully.");
                    return Mono.just(access);
                })
                .onErrorMap(e -> new ChzzkApiException("Failed to refresh token: " + e.getMessage(), e));
    }

    private void storeTokens(String access, String refresh, long expiresInSeconds) {
        accessToken.set(access);
        if (refresh != null) refreshToken.set(refresh);
        // 만료 60초 전을 실제 만료 시간으로 간주 (네트워크 지연 등 고려)
        expiresAt.set(Instant.now().plusSeconds(expiresInSeconds - 60));
    }

    private boolean hasValidToken() {
        String token = accessToken.get();
        Instant expiry = expiresAt.get();
        return token != null && expiry.isAfter(Instant.now());
    }

    private long parseLong(Object obj) {
        if (obj instanceof Number num) return num.longValue();
        if (obj instanceof String str) return Long.parseLong(str);
        return 86400L; // 기본 1일
    }
}
