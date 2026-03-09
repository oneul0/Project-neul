package com.neul.collector.chzzk;

import com.neul.collector.config.ChzzkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chzzk Open API HTTP 클라이언트.
 * 클라이언트 인증(Client Credentials) 방식으로 동작.
 * - 클라이언트 액세스 토큰 발급 및 자동 갱신 (인메모리 캐시)
 * - 세션 URL 발급 (/open/v1/sessions/auth/client)
 * - 이벤트 구독/취소 (/open/v1/sessions/events/subscribe|unsubscribe/{type})
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChzzkApiClient {

    private final ChzzkProperties props;
    private final WebClient chzzkWebClient;

    // 인메모리 클라이언트 토큰 캐시 (스레드 안전)
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // ─── 토큰 관리 ───────────────────────────────────────────────────────────

    /**
     * 클라이언트 액세스 토큰을 반환. 만료 10분 전이면 자동 갱신.
     */
    public Mono<String> getClientToken() {
        if (isTokenValid()) {
            return Mono.just(cachedToken.get());
        }
        return fetchNewClientToken();
    }

    private boolean isTokenValid() {
        String token = cachedToken.get();
        return token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(600));
    }

    @SuppressWarnings("unchecked")
    private Mono<String> fetchNewClientToken() {
        log.info("[Chzzk] Fetching new client access token...");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grantType", "client_credentials");
        body.add("clientId", props.getClientId());
        body.add("clientSecret", props.getClientSecret());

        return chzzkWebClient.post()
                .uri("/auth/v1/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(body))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    String token = (String) response.get("accessToken");
                    int expiresIn = Integer.parseInt(String.valueOf(response.get("expiresIn")));
                    cachedToken.set(token);
                    tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
                    log.info("[Chzzk] Client token acquired. Expires in {}s", expiresIn);
                    return token;
                })
                .onErrorMap(e -> {
                    log.error("[Chzzk] Failed to fetch client token: {}", e.getMessage());
                    return new ChzzkApiException("Failed to acquire Chzzk client token", e);
                });
    }

    // ─── 세션 ────────────────────────────────────────────────────────────────

    /**
     * 클라이언트 인증 기반 Socket.IO 세션 URL 발급.
     *
     * @return Socket.IO 연결 URL (시간 제한 있음)
     */
    @SuppressWarnings("unchecked")
    public Mono<String> createClientSession() {
        return getClientToken().flatMap(token -> chzzkWebClient.get()
                .uri("/open/v1/sessions/auth/client")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    String url = (String) content.get("url");
                    log.info("[Chzzk] Session URL issued: {}", url);
                    return url;
                })
                .onErrorMap(e -> new ChzzkApiException("Failed to create Chzzk session", e)));
    }

    // ─── 이벤트 구독 ─────────────────────────────────────────────────────────

    /**
     * 채팅 이벤트 구독.
     *
     * @param sessionKey 세션 연결 완료 후 수신한 sessionKey
     */
    public Mono<Void> subscribeChatEvent(String sessionKey) {
        return subscribeEvent("chat", sessionKey);
    }

    /**
     * 후원 이벤트 구독.
     */
    public Mono<Void> subscribeDonationEvent(String sessionKey) {
        return subscribeEvent("donation", sessionKey);
    }

    /**
     * 구독 알림 이벤트 구독 (치지직 채널 구독 = Subscription).
     */
    public Mono<Void> subscribeSubscriptionEvent(String sessionKey) {
        return subscribeEvent("subscription", sessionKey);
    }

    /**
     * 특정 이벤트 타입 구독 취소.
     */
    public Mono<Void> unsubscribeEvent(String eventType, String sessionKey) {
        return getClientToken().flatMap(token -> chzzkWebClient.post()
                .uri("/open/v1/sessions/events/unsubscribe/" + eventType + "?sessionKey=" + sessionKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.info("[Chzzk] Unsubscribed {} event. Session: {}", eventType, sessionKey))
                .then());
    }

    private Mono<Void> subscribeEvent(String eventType, String sessionKey) {
        return getClientToken().flatMap(token -> chzzkWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/open/v1/sessions/events/subscribe/" + eventType)
                        .queryParam("sessionKey", sessionKey)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.info("[Chzzk] Subscribed {} event. Session: {}", eventType, sessionKey))
                .then()
                .onErrorMap(e -> new ChzzkApiException("Failed to subscribe " + eventType + " event", e)));
    }

    // ─── 라이브 목록 조회 ────────────────────────────────────────────────────

    /**
     * 현재 진행 중인 라이브 목록 조회 (core-api에서도 호출 가능하도록 범용 메서드).
     *
     * @param size 조회 개수 (1~20, 기본 20)
     * @param next 다음 페이지 커서 (nullable)
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getLives(int size, String next) {
        return getClientToken().flatMap(token -> {
            var spec = chzzkWebClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/open/v1/lives").queryParam("size", size);
                        if (next != null && !next.isBlank()) {
                            builder.queryParam("next", next);
                        }
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            return spec.retrieve()
                    .bodyToMono(Map.class)
                    .map(response -> (Map<String, Object>) response.get("content"))
                    .onErrorMap(e -> new ChzzkApiException("Failed to fetch live list", e));
        });
    }
}
