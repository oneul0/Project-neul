package com.neul.core_api.config;

import com.neul.common.auth.OwnerTokenCodec;
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

@Slf4j
@Component
public class OwnerAccessFilter implements WebFilter {

    @Value("${neul.owner-token-secret:dev-owner-token-secret}")
    private String ownerTokenSecret;

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

        String ownerId = null;
        if (exchange.getRequest().getCookies().containsKey("NEUL_OWNER_ASSERTION")) {
            ownerId = OwnerTokenCodec.verifyAndExtractOwner(
                    exchange.getRequest().getCookies().getFirst("NEUL_OWNER_ASSERTION").getValue(),
                    ownerTokenSecret);
        }
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = exchange.getRequest().getHeaders().getFirst("X-Chzzk-Owner-Id");
        }
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = exchange.getRequest().getQueryParams().getFirst("ownerId");
        }

        if (ownerId == null || ownerId.isBlank()) {
            log.warn("[OwnerAccess] Missing owner id for path: {}", path);
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "login_required", "CHZZK login is required.");
        }

        String targetRoomId = extractRoomId(path);
        if (targetRoomId == null || targetRoomId.isBlank()) {
            return chain.filter(exchange);
        }

        if (!ownerId.equals(targetRoomId)) {
            log.warn("[OwnerAccess] Ownership mismatch. ownerId={}, targetRoomId={}", ownerId, targetRoomId);
            return respondWithError(exchange, HttpStatus.FORBIDDEN, "forbidden", "You can only access your own channel dashboard.");
        }

        return chain.filter(exchange);
    }

    private boolean isProtectedPath(String path, HttpMethod method) {
        // 룰렛 상태 조회(GET)는 공개 — 시청자도 현황을 볼 수 있어야 함
        if (HttpMethod.GET == method && path.startsWith("/api/v1/roulette/")) return false;
        return path.startsWith("/api/v1/stream/")
                || path.startsWith("/api/v1/poll/")
                || path.startsWith("/api/v1/donations/")
                || path.startsWith("/api/v1/roulette/")
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
