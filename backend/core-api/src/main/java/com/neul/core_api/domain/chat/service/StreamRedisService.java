package com.neul.core_api.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamRedisService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<Void> updateMultiEmotionStats(String roomId, Map<String, Double> scores) {
        String key = "room:" + roomId + ":stats";
        
        return Flux.fromIterable(scores.entrySet())
                .filter(entry -> entry.getValue() > 0)
                .flatMap(entry -> redisTemplate.opsForHash().increment(key, entry.getKey(), entry.getValue()))
                .then(redisTemplate.opsForHash().increment(key, "TOTAL_COUNT", 1))
                .then();
    }

    public Mono<Map<Object, Object>> getRoomStats(String roomId) {
        String key = "room:" + roomId + ":stats";
        return redisTemplate.opsForHash().entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(e -> {
                    log.warn("Redis 접속 실패로 인해 [{}] 감정 통계를 가져올 수 없습니다: {}", roomId, e.getMessage());
                    return Mono.just(Map.of());
                });
    }

    // ─── Voting & Session Control (Phase 23) ─────────────────────────────────

    public Mono<Boolean> recordVote(String roomId, String userId, String option) {
        String key = "poll:" + roomId + ":votes";
        // Latest-Vote-Only: overwrites previous field value for the same userId
        return redisTemplate.opsForHash().put(key, userId, option);
    }

    public Mono<Map<Object, Object>> getPollResults(String roomId) {
        String key = "poll:" + roomId + ":votes";
        return redisTemplate.opsForHash().entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public Mono<Void> clearPoll(String roomId) {
        return redisTemplate.delete("poll:" + roomId + ":votes").then();
    }

    public Mono<Boolean> setCollectionActive(String roomId, boolean active) {
        String key = "room:" + roomId + ":session";
        return redisTemplate.opsForValue().set(key, active);
    }

    public Mono<Boolean> isCollectionActive(String roomId) {
        String key = "room:" + roomId + ":session";
        return redisTemplate.opsForValue().get(key)
                .map(val -> {
                    if (val instanceof Boolean) return (Boolean) val;
                    if (val instanceof String) return Boolean.parseBoolean((String) val);
                    return false;
                })
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> setPollItems(String roomId, java.util.List<String> items) {
        String key = "poll:" + roomId + ":items";
        return redisTemplate.delete(key)
                .then(redisTemplate.opsForList().rightPushAll(key, items.toArray()))
                .map(res -> true);
    }

    public Mono<java.util.List<String>> getPollItems(String roomId) {
        String key = "poll:" + roomId + ":items";
        return redisTemplate.opsForList().range(key, 0, -1)
                .map(Object::toString)
                .collectList();
    }

    public Mono<Map<Object, Object>> recordKeywordGroupsAndGetStats(String roomId, Map<String, String> keywordGroups) {
        String key = "vote:keywords:" + roomId;
        return Flux.fromIterable(keywordGroups.entrySet())
                .flatMap(entry -> redisTemplate.opsForHash().increment(key, entry.getValue(), 1))
                .then(redisTemplate.opsForHash().entries(key)
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
