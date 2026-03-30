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
        // ─── 인증 정보 부재 시 Mock 데이터 제공 (500 에러 방지) ───
        if (chzzkProperties.getClientId() == null || chzzkProperties.getClientId().contains("CHZZK_CLIENT_ID") ||
            chzzkProperties.getClientSecret() == null || chzzkProperties.getClientSecret().contains("CHZZK_CLIENT_SECRET")) {
            log.warn("[ChzzkLiveService] 치지직 API 인증 정보가 설정되지 않았습니다. Mock 데이터를 반환합니다.");
            return Mono.just(createMockResponse(size));
        }

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
                .onStatus(status -> status.value() == 401, response -> {
                    log.error("치지직 OpenAPI 인증 실패 (401 Unauthorized)");
                    return Mono.error(new RuntimeException("CHZZK_AUTH_FAILED"));
                })
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(e -> {
                    if (e.getMessage().equals("CHZZK_AUTH_FAILED")) {
                        return Mono.just(createMockResponse(size));
                    }
                    log.error("치지직 API 호출 중 예외 발생: ", e);
                    return Mono.just(Map.of("code", 500, "message", e.getMessage()));
                })
                .flatMap(response -> {
                    if (response.containsKey("code") && (int)response.get("code") != 200) {
                        return Mono.just(response);
                    }
                    
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
                                return response;
                            });
                });
    }

    private Map<String, Object> createMockResponse(int size) {
        Map<String, Object> mockLive = new HashMap<>();
        mockLive.put("liveId", 0);
        mockLive.put("liveTitle", "[안내] 치지직 API 키가 설정되지 않아 샘플 데이터를 표시합니다.");
        mockLive.put("channelId", "mock_channel");
        mockLive.put("channelName", "네울 시스템");
        mockLive.put("concurrentUserCount", 0);
        mockLive.put("liveCategoryValue", "시스템");
        mockLive.put("liveImageUrl", "https://via.placeholder.com/320x180?text=API+Key+Required");
        
        Map<String, Object> content = new HashMap<>();
        content.put("size", 1);
        content.put("data", List.of(mockLive));
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "MOCK_OK");
        response.put("content", content);
        return response;
    }
}
