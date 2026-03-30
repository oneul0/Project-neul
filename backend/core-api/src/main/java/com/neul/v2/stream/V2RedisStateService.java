package com.neul.v2.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.v2.common.dto.V2AggregateFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2RedisStateService {

    private static final Duration FRAME_TTL = Duration.ofHours(6);
    private static final Duration TRUST_TTL = Duration.ofHours(6);

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<Void> saveLatestFrame(V2AggregateFrame frame) {
        String frameKey = latestFrameKey(frame.getRoomId());
        String trustKey = trustSummaryKey(frame.getRoomId());

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(frame))
                .flatMap(json -> redisTemplate.opsForValue().set(frameKey, json))
                .then(redisTemplate.expire(frameKey, FRAME_TTL))
                .then(saveTrustSummary(trustKey, frame.getTrustSummary()))
                .onErrorResume(error -> {
                    log.warn("[V2RedisStateService] Failed to persist latest v2 frame for roomId={}: {}",
                            frame.getRoomId(), error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    public Mono<V2AggregateFrame> getLatestFrame(String roomId) {
        return redisTemplate.opsForValue().get(latestFrameKey(roomId))
                .cast(String.class)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, V2AggregateFrame.class));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("[V2RedisStateService] Failed to restore latest v2 frame for roomId={}: {}",
                            roomId, error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Map<String, Object>> getTrustSummary(String roomId) {
        return redisTemplate.opsForHash().entries(trustSummaryKey(roomId))
                .collectMap(entry -> entry.getKey().toString(), Map.Entry::getValue)
                .map(entries -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    entries.forEach((key, value) -> normalized.put(key, value));
                    return normalized;
                })
                .defaultIfEmpty(Map.of());
    }

    private Mono<Void> saveTrustSummary(String trustKey, Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return Mono.empty();
        }

        Map<String, Object> payload = new LinkedHashMap<>(summary);
        return redisTemplate.opsForHash().putAll(trustKey, payload)
                .then(redisTemplate.expire(trustKey, TRUST_TTL))
                .then();
    }

    private String latestFrameKey(String roomId) {
        return "v2:room:" + roomId + ":latest-frame";
    }

    private String trustSummaryKey(String roomId) {
        return "v2:room:" + roomId + ":trust-summary";
    }
}
