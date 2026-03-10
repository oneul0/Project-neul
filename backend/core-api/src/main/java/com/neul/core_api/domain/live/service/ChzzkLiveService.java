package com.neul.core_api.domain.live.service;

import com.neul.core_api.config.ChzzkProperties;
import com.neul.core_api.domain.chat.service.StreamRedisService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Chzzk 라이브 채널 목록 조회 서비스.
 * - Chzzk 라이브 목록 API 호출
 * - 각 채널의 Redis 감정 통계와 병합하여 응답
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkLiveService {

    private final ChzzkProperties chzzkProperties;
    private final StreamRedisService streamRedisService;

    // ─── 라이브 목록 + 감정 통계 병합 ────────────────────────────────────────

    public Mono<Map<String, Object>> getLivesWithSentiment(int size, String next) {
        String uri = "/open/v1/lives?size=" + size;
        if (next != null && !next.isBlank()) {
            uri += "&next=" + next;
        }

        return WebClient.builder().baseUrl(chzzkProperties.getBaseUrl()).build()
                .get()
                .uri(uri)
                .header("Client-Id", chzzkProperties.getClientId())
                .header("Client-Secret", chzzkProperties.getClientSecret())
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("치지직 라이브 목록 조회 에러: ", e))
                .flatMap(response -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> content = (Map<String, Object>) response.get("content");
                                if (content == null) return Mono.just(response);

                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> data = (List<Map<String, Object>>) content.get("data");
                                if (data == null || data.isEmpty()) return Mono.just(response);

                                return Flux.fromIterable(data)
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
                                            content.put("data", enrichedLives);
                                            response.put("content", content);
                                            return response; // 최상위 구조 그대로 반환
                                        });
                            });
    }
}
