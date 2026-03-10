package com.neul.core_api.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamRedisService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<Void> incrementEmotionStats(String roomId, String emotionType) {
        String key = "room:" + roomId + ":stats";
        
        // 1. 해당 감정 타입 카운트 증가
        // 2. 전체 카운트 증가
        return redisTemplate.opsForHash().increment(key, emotionType, 1)
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
}
