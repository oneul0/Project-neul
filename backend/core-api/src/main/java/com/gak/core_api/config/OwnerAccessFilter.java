package com.gak.core_api.config;

import com.gak.common.auth.OwnerTokenCodec;
import com.gak.common.auth.OwnerTokenCodec.OwnerClaims;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerAccessFilter implements WebFilter {

    /** 검증된 ownerId를 후속 핸들러에 전달하기 위한 exchange attribute 키. */
    public static final String ATTR_OWNER_ID = "gak.ownerId";

    private static final String INSECURE_DEFAULT = "dev-gak-token-secret";
    private static final String SESSION_KEY_PREFIX = "gak:owner-session:";

    private final ReactiveStringRedisTemplate redisTemplate;

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
        HttpMethod method = exchange.getRequest().getMethod();
        if (!isProtectedPath(path, method)) {
            return chain.filter(exchange);
        }

        OwnerClaims claims = null;
        if (exchange.getRequest().getCookies().containsKey("GAK_OWNER_ASSERTION")) {
            claims = OwnerTokenCodec.verifyAndExtractClaims(
                    exchange.getRequest().getCookies().getFirst("GAK_OWNER_ASSERTION").getValue(),
                    ownerTokenSecret);
        }

        if (claims == null) {
            log.warn("[OwnerAccess] Missing or invalid owner assertion for path: {}", path);
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "login_required", "CHZZK login is required.");
        }

        final OwnerClaims finalClaims = claims;

        // Redis에서 현재 유효한 sessionId 조회 — 로그아웃/탈취 시 즉시 차단
        return redisTemplate.opsForValue()
                .get(SESSION_KEY_PREFIX + finalClaims.ownerId())
                .flatMap(storedSessionId -> {
                    if (!finalClaims.sessionId().equals(storedSessionId)) {
                        log.warn("[OwnerAccess] Session mismatch for ownerId={}. Possible theft or stale token.", finalClaims.ownerId());
                        return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "session_invalid", "Session is no longer valid. Please log in again.");
                    }

                    String targetRoomId = extractRoomId(path);
                    if (targetRoomId != null && !targetRoomId.isBlank() && !finalClaims.ownerId().equals(targetRoomId)) {
                        log.warn("[OwnerAccess] Ownership mismatch. ownerId={}, targetRoomId={}", finalClaims.ownerId(), targetRoomId);
                        return respondWithError(exchange, HttpStatus.FORBIDDEN, "forbidden", "You can only access your own channel dashboard.");
                    }

                    // 검증 통과 — 이후 핸들러가 재검증 없이 ownerId를 읽을 수 있도록 주입
                    exchange.getAttributes().put(ATTR_OWNER_ID, finalClaims.ownerId());
                    return chain.filter(exchange);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[OwnerAccess] No active session found for ownerId={}.", finalClaims.ownerId());
                    return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "session_expired", "Session expired. Please log in again.");
                }));
    }

    private boolean isProtectedPath(String path, HttpMethod method) {
        // GET 조회는 공개 — 비로그인도 볼 수 있고, 로그인 시 개인화 적용
        if (HttpMethod.GET == method && path.startsWith("/api/v1/roulette/")) return false;
        if (HttpMethod.GET == method && path.startsWith("/api/v1/poll/")) return false;
        if (HttpMethod.GET == method && path.startsWith("/api/v1/vod/")) return false;
        return path.startsWith("/api/v1/stream/")
                || path.startsWith("/api/v1/poll/")
                || path.startsWith("/api/v1/donations/")
                || path.startsWith("/api/v1/roulette/")
                || path.startsWith("/api/v1/vod/")
                || path.startsWith("/api/v1/me/")
                || path.startsWith("/api/v2/stream/")
                || path.startsWith("/api/v2/state/");
    }

    private String extractRoomId(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (("stream".equals(segments[i]) || "poll".equals(segments[i])
                    || "donations".equals(segments[i]) || "roulette".equals(segments[i])
                    || "state".equals(segments[i]))
                    && i + 1 < segments.length) {
                return segments[i + 1];
            }
        }
        return null;
    }

    private Mono<Void> respondWithError(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", error, message);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
