package com.gak.collector.config;

import com.gak.collector.auth.ChzzkSessionRegistry;
import com.gak.common.auth.OwnerTokenCodec;
import com.gak.common.auth.OwnerTokenCodec.OwnerClaims;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * WebFlux 기반의 채널 소유권 검증 필터.
 * 서명된 GAK_OWNER_ASSERTION 쿠키 검증 후 Redis 세션 바인딩을 추가로 확인합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerValidationFilter implements WebFilter {

    private static final String INSECURE_DEFAULT = "dev-gak-token-secret";

    private final ChzzkSessionRegistry sessionRegistry;

    @Value("${gak.owner-token-secret:dev-gak-token-secret}")
    private String ownerTokenSecret;

    @PostConstruct
    void warnIfInsecureSecret() {
        if (INSECURE_DEFAULT.equals(ownerTokenSecret)) {
            log.warn("[Security] gak.owner-token-secret is using the insecure default value. Set a strong secret via environment variable before deploying.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();

        if (!path.contains("/subscribe")) {
            return chain.filter(exchange);
        }

        OwnerClaims claims = null;
        if (exchange.getRequest().getCookies().containsKey("GAK_OWNER_ASSERTION")) {
            claims = OwnerTokenCodec.verifyAndExtractClaims(
                    exchange.getRequest().getCookies().getFirst("GAK_OWNER_ASSERTION").getValue(),
                    ownerTokenSecret);
        }

        if (claims == null) {
            log.warn("[Auth] Missing or invalid owner assertion cookie for path: {}", path);
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "login_required", "CHZZK login is required.");
        }

        final OwnerClaims finalClaims = claims;

        // Redis에서 channelId에 매핑된 sessionId 조회 — 토큰의 sessionId와 일치해야 함
        return sessionRegistry.getSessionId(finalClaims.ownerId())
                .flatMap(storedSessionId -> {
                    if (!finalClaims.sessionId().equals(storedSessionId)) {
                        log.warn("[Auth] Session mismatch for ownerId={}. Token may be stolen or reused after logout.", finalClaims.ownerId());
                        return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "session_invalid", "Session is no longer valid. Please log in again.");
                    }

                    String targetChannelId = extractChannelId(path);
                    if (!finalClaims.ownerId().equals(targetChannelId)) {
                        log.warn("[Auth] Ownership mismatch. Owner: {}, Target: {}", finalClaims.ownerId(), targetChannelId);
                        return respondWithError(exchange, HttpStatus.FORBIDDEN, "forbidden", "You can only collect chat for your own channel.");
                    }

                    return chain.filter(exchange);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[Auth] No active session found for ownerId={}. Session may have expired.", finalClaims.ownerId());
                    return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "session_expired", "Session expired. Please log in again.");
                }));
    }

    private String extractChannelId(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if ("channels".equals(segments[i]) && i + 1 < segments.length) {
                return segments[i + 1];
            }
        }
        return "";
    }

    private Mono<Void> respondWithError(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\": \"%s\", \"message\": \"%s\"}", error, message);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
