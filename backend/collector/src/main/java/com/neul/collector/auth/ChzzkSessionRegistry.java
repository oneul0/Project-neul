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

    // 7일 — 토큰 만료(1h)와 무관한 revocation 전용 키이므로 충분히 긴 TTL을 사용.
    // 실제 만료 판단은 토큰 페이로드의 expiresAt으로 하며, 이 키는 로그아웃 시 삭제된다.
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    public Mono<Void> register(String channelId, String sessionId, long ignoredTokenTtl) {
        return redisTemplate.opsForValue()
                .set(key(channelId), sessionId, SESSION_TTL)
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
