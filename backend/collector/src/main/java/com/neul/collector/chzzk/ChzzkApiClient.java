package com.neul.collector.chzzk;

import com.neul.collector.config.ChzzkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Chzzk Open API HTTP 클라이언트.
 *
 * 인증 방식:
 *  - Client 인증 (Client-Id/Client-Secret 헤더): 세션 생성, 라이브 목록 조회
 *  - Access Token 인증 (Authorization: Bearer): 이벤트 구독/취소
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChzzkApiClient {

    private final ChzzkProperties props;
    private final WebClient chzzkWebClient;
    private final ChzzkTokenService tokenService;

    // ─── 세션 ────────────────────────────────────────────────────────────────

    /**
     * 클라이언트 인증 기반 Socket.IO 세션 URL 발급.
     * (Client Auth 방식 - Client-Id/Client-Secret 헤더 사용)
     *
     * @return Socket.IO 연결 URL (시간 제한 있음)
     */
    @SuppressWarnings("unchecked")
    public Mono<String> createClientSession() {
        return chzzkWebClient.get()
                .uri("/open/v1/sessions/auth/client")
                .header("Client-Id", props.getClientId())
                .header("Client-Secret", props.getClientSecret())
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    String url = (String) content.get("url");
                    log.info("[Chzzk] Client Session URL issued: {}", url);
                    return url;
                })
                .onErrorMap(e -> new ChzzkApiException("Failed to create Chzzk client session", e));
    }

    /**
     * 유저(Access Token) 인증 기반 Socket.IO 세션 URL 발급.
     * 이벤트 구독은 이 세션에서만 가능합니다.
     * (Access Token Auth 방식 - Authorization: Bearer 헤더 사용)
     *
     * @return Socket.IO 연결 URL (시간 제한 있음)
     */
    @SuppressWarnings("unchecked")
    public Mono<String> createUserSession() {
        return tokenService.getValidAccessToken()
                .flatMap(token -> chzzkWebClient.get()
                        .uri("/open/v1/sessions/auth")
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .onStatus(org.springframework.http.HttpStatusCode::isError, response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(new Exception("API Error: [" + response.statusCode() + "] " + body)))
                        )
                        .bodyToMono(Map.class)
                        .map(response -> {
                            Map<String, Object> content = (Map<String, Object>) response.get("content");
                            String url = (String) content.get("url");
                            log.info("[Chzzk] User Session URL issued: {}", url);
                            return url;
                        })
                )
                .onErrorMap(e -> new ChzzkApiException("Failed to create Chzzk user session: " + e.getMessage(), e));
    }

    // ─── 이벤트 구독 (Access Token 인증 필수) ─────────────────────────────────

    /**
     * 채팅 이벤트 구독.
     * Access Token 인증 방식 (Authorization: Bearer) 사용.
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
     * Access Token 인증 방식 사용.
     */
    public Mono<Void> unsubscribeEvent(String eventType, String sessionKey) {
        return tokenService.getValidAccessToken()
                .flatMap(token -> chzzkWebClient.post()
                        .uri("/open/v1/sessions/events/unsubscribe/" + eventType + "?sessionKey=" + sessionKey)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity()
                        .doOnSuccess(r -> log.info("[Chzzk] Unsubscribed {} event. Session: {}", eventType, sessionKey))
                        .then());
    }

    /**
     * 이벤트 구독 내부 메서드.
     * Access Token 인증 방식으로 구독 요청.
     */
    private Mono<Void> subscribeEvent(String eventType, String sessionKey) {
        return tokenService.getValidAccessToken()
                .flatMap(token -> chzzkWebClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/open/v1/sessions/events/subscribe/" + eventType)
                                .queryParam("sessionKey", sessionKey)
                                .build())
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .onStatus(org.springframework.http.HttpStatusCode::isError, response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(new Exception("API Error: [" + response.statusCode() + "] " + body)))
                        )
                        .toBodilessEntity()
                        .doOnSuccess(r -> log.info("[Chzzk] Subscribed {} event. Session: {}", eventType, sessionKey))
                        .then()
                )
                .onErrorMap(e -> new ChzzkApiException("Failed to subscribe " + eventType + " event: " + e.getMessage(), e));
    }

    // ─── 라이브 목록 조회 (Client 인증) ──────────────────────────────────────

    /**
     * 현재 진행 중인 라이브 목록 조회.
     * Client 인증 방식 (Client-Id/Client-Secret 헤더) 사용.
     *
     * @param size 조회 개수 (1~20, 기본 20)
     * @param next 다음 페이지 커서 (nullable)
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getLives(int size, String next) {
        var spec = chzzkWebClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/open/v1/lives").queryParam("size", size);
                    if (next != null && !next.isBlank()) {
                        builder.queryParam("next", next);
                    }
                    return builder.build();
                })
                .header("Client-Id", props.getClientId())
                .header("Client-Secret", props.getClientSecret());

        return spec.retrieve()
                .bodyToMono(Map.class)
                .map(response -> (Map<String, Object>) response.get("content"))
                .onErrorMap(e -> new ChzzkApiException("Failed to fetch live list", e));
    }
}
