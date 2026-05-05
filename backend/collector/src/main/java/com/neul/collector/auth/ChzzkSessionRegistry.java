package com.neul.collector.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * channelId → sessionId 매핑을 Redis에 보관한다.
 *
 * 로그인 시 등록, 로그아웃 시 삭제. NidChatCollector가 subscribe 요청 시점에
 * channelId만으로 access_token을 꺼낼 수 있도록 하는 브릿지 역할을 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChzzkSessionRegistry {

    private static final String KEY_PREFIX = "neul:owner-session:";
    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<Void> register(String channelId, String sessionId, long ttlSeconds) {
        return redisTemplate.opsForValue()
                .set(key(channelId), sessionId, Duration.ofSeconds(Math.max(ttlSeconds, 60)))
                .doOnSuccess(v -> log.info("[SessionRegistry] Registered channelId={}", channelId))
                .then();
    }

    public Mono<String> getSessionId(String channelId) {
        return redisTemplate.opsForValue().get(key(channelId));
    }

    public Mono<Void> unregister(String channelId) {
        return redisTemplate.delete(key(channelId))
                .doOnSuccess(v -> log.info("[SessionRegistry] Unregistered channelId={}", channelId))
                .then();
    }

    private String key(String channelId) {
        return KEY_PREFIX + channelId;
    }
}
