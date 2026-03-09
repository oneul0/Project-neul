package com.neul.core_api.domain.live.service;

import com.neul.core_api.config.ChzzkProperties;
import com.neul.core_api.domain.chat.service.StreamRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chzzk 라이브 채널 목록 조회 서비스.
 * - Chzzk 라이브 목록 API 호출
 * - 각 채널의 Redis 감정 통계와 병합하여 응답
 */
@Service
@RequiredArgsConstructor
public class ChzzkLiveService {

    private final ChzzkProperties chzzkProperties;
    private final StreamRedisService streamRedisService;

    // 인메모리 클라이언트 토큰 캐시
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    private WebClient buildWebClient() {
        return WebClient.builder()
                .baseUrl(chzzkProperties.getBaseUrl())
                .build();
    }

    // ─── 토큰 관리 ───────────────────────────────────────────────────────────

    public Mono<String> getClientToken() {
        if (cachedToken.get() != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(600))) {
            return Mono.just(cachedToken.get());
        }
        return fetchNewToken();
    }

    private Mono<String> fetchNewToken() {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grantType", "client_credentials");
        body.add("clientId", chzzkProperties.getClientId());
        body.add("clientSecret", chzzkProperties.getClientSecret());

        return buildWebClient().post()
                .uri("/auth/v1/token")
                .body(BodyInserters.fromFormData(body))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    String token = (String) response.get("accessToken");
                    int expiresIn = Integer.parseInt(String.valueOf(response.get("expiresIn")));
                    cachedToken.set(token);
                    tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
                    return token;
                });
    }

    // ─── 라이브 목록 + 감정 통계 병합 ────────────────────────────────────────

    /**
     * Chzzk 라이브 목록을 조회하고, 각 채널의 Redis 감정 통계를 병합합니다.
     *
     * @param size 조회 개수 (1~20)
     * @param next 다음 페이지 커서 (nullable)
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getLivesWithSentiment(int size, String next) {
        return getClientToken().flatMap(token -> buildWebClient().get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/open/v1/lives").queryParam("size", size);
                    if (next != null && !next.isBlank()) {
                        builder.queryParam("next", next);
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(response -> {
                    Map<String, Object> content = (Map<String, Object>) response.get("content");
                    List<Map<String, Object>> lives = (List<Map<String, Object>>) content.get("data");
                    Map<String, Object> page = (Map<String, Object>) content.get("page");

                    if (lives == null || lives.isEmpty()) {
                        return Mono.just(Map.<String, Object>of("data", List.of(), "page", page));
                    }

                    // 각 채널에 대해 Redis 감정 통계를 병합
                    return Flux.fromIterable(lives)
                            .flatMap(live -> {
                                String channelId = (String) live.get("channelId");
                                return streamRedisService.getRoomStats(channelId)
                                        .map(stats -> {
                                            Map<String, Object> enriched = new HashMap<>(live);
                                            enriched.put("sentiment", stats.isEmpty()
                                                    ? Map.of("TOTAL_COUNT", 0)
                                                    : stats);
                                            return enriched;
                                        })
                                        .defaultIfEmpty(live);
                            })
                            .collectList()
                            .map(enrichedLives -> {
                                Map<String, Object> result = new HashMap<>();
                                result.put("data", enrichedLives);
                                result.put("page", page);
                                return result;
                            });
                }));
    }
}
