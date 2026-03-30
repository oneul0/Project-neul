package com.neul.collector.config;

import com.neul.common.auth.OwnerTokenCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
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
 * 요청 헤더의 X-Chzzk-Owner-Id와 요청 경로의 channelId를 비교하여 본인 채널 여부를 확인합니다.
 */
@Slf4j
@Component
public class OwnerValidationFilter implements WebFilter {

    @Value("${neul.owner-token-secret:dev-owner-token-secret}")
    private String ownerTokenSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 구독 관련 API만 체크 (/api/v1/channels/{channelId}/subscribe)
        if (path.contains("/subscribe")) {
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
                log.warn("[Auth] Missing X-Chzzk-Owner-Id header for path: {}", path);
                return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "login_required", "CHZZK login is required.");
            }

            // 경로에서 channelId 추출
            String[] segments = path.split("/");
            String targetChannelId = "";
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].equals("channels") && i + 1 < segments.length) {
                    targetChannelId = segments[i + 1];
                    break;
                }
            }

            if (!ownerId.equals(targetChannelId)) {
                log.warn("[Auth] Ownership mismatch. Owner: {}, Target: {}", ownerId, targetChannelId);
                return respondWithError(exchange, HttpStatus.FORBIDDEN, "forbidden", "You can only collect chat for your own channel.");
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> respondWithError(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\": \"%s\", \"message\": \"%s\"}", error, message);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
