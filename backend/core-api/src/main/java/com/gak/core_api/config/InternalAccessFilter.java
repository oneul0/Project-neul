package com.gak.core_api.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
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
 * /internal/** 경로를 서비스 간 공유 시크릿(X-Internal-Secret)으로 보호합니다.
 * 시크릿이 일치하지 않으면 404를 반환해 경로 존재 자체를 노출하지 않습니다.
 */
@Slf4j
@Order(-10)
@Component
public class InternalAccessFilter implements WebFilter {

    private static final String INSECURE_DEFAULT = "dev-internal-secret";
    private static final String SECRET_HEADER = "X-Internal-Secret";

    @Value("${gak.internal-api-secret:dev-internal-secret}")
    private String internalApiSecret;

    @PostConstruct
    void warnIfInsecureSecret() {
        if (INSECURE_DEFAULT.equals(internalApiSecret)) {
            log.warn("[Security] gak.internal-api-secret is using the insecure default value. Set a strong secret via environment variable before deploying.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/internal/")) {
            return chain.filter(exchange);
        }

        String secret = exchange.getRequest().getHeaders().getFirst(SECRET_HEADER);
        if (internalApiSecret.equals(secret)) {
            return chain.filter(exchange);
        }

        log.warn("[InternalAccess] Rejected request to internal path: {}", path);
        return respondNotFound(exchange);
    }

    private Mono<Void> respondNotFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"not_found\"}";
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
