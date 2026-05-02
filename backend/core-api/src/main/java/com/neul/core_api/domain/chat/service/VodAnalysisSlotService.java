package com.neul.core_api.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * VOD 분석 동시성 가드레일.
 *
 * - 사용자별 동시 분석 1건 / 시스템 전체 3건 제한
 * - Redis에 슬롯 카운터와 videoNo→ownerId 매핑을 저장해 분석 완료/실패 시 반납
 * - TTL(30분) 설정으로 stuck 상태가 되어도 자동 만료
 *
 * 설계 근거: docs/14_vod_concurrency_plan.md
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodAnalysisSlotService {

    private static final int MAX_PER_USER = 1;
    private static final int MAX_GLOBAL = 3;
    private static final Duration SLOT_TTL = Duration.ofMinutes(30);

    private final ReactiveStringRedisTemplate stringRedisTemplate;

    /**
     * 분석 시작 전 슬롯 획득 시도.
     * videoNo→ownerId 매핑을 저장해두어 releaseByVideoNo()에서 반납에 활용.
     */
    public Mono<SlotResult> tryAcquire(String ownerId, String videoNo) {
        String ownerMappingKey = "vod:owner:" + videoNo;
        String userKey = "vod:active:user:" + ownerId;
        String globalKey = "vod:active:global";

        return stringRedisTemplate.opsForValue().increment(globalKey)
                .flatMap(globalCount -> {
                    if (globalCount > MAX_GLOBAL) {
                        return stringRedisTemplate.opsForValue().decrement(globalKey)
                                .thenReturn(SlotResult.REJECTED_GLOBAL);
                    }
                    return stringRedisTemplate.opsForValue().increment(userKey)
                            .flatMap(userCount -> {
                                if (userCount > MAX_PER_USER) {
                                    return Mono.zip(
                                            stringRedisTemplate.opsForValue().decrement(userKey),
                                            stringRedisTemplate.opsForValue().decrement(globalKey)
                                    ).thenReturn(SlotResult.REJECTED_USER);
                                }
                                return Mono.when(
                                        stringRedisTemplate.opsForValue().set(ownerMappingKey, ownerId, SLOT_TTL),
                                        stringRedisTemplate.expire(userKey, SLOT_TTL),
                                        stringRedisTemplate.expire(globalKey, SLOT_TTL)
                                ).thenReturn(SlotResult.ACQUIRED);
                            });
                })
                .doOnError(e -> log.error("[SlotService] Failed to acquire slot ownerId={}, videoNo={}", ownerId, videoNo, e))
                .onErrorReturn(SlotResult.ACQUIRED); // Redis 장애 시 분석은 허용 (fail-open)
    }

    /**
     * 분석 완료 또는 실패 시 슬롯 반납.
     * VodAnalysisEventConsumer가 Kafka 이벤트 수신 후 호출.
     */
    public Mono<Void> releaseByVideoNo(String videoNo) {
        String ownerMappingKey = "vod:owner:" + videoNo;

        return stringRedisTemplate.opsForValue().get(ownerMappingKey)
                .flatMap(ownerId -> {
                    if (ownerId == null || ownerId.isBlank()) {
                        return Mono.empty();
                    }
                    log.debug("[SlotService] Releasing slot for ownerId={}, videoNo={}", ownerId, videoNo);
                    return Mono.when(
                            stringRedisTemplate.opsForValue().decrement("vod:active:user:" + ownerId),
                            stringRedisTemplate.opsForValue().decrement("vod:active:global"),
                            stringRedisTemplate.delete(ownerMappingKey)
                    );
                })
                .then()
                .doOnError(e -> log.warn("[SlotService] Failed to release slot for videoNo={}", videoNo, e))
                .onErrorResume(e -> Mono.empty());
    }

    public enum SlotResult {
        ACQUIRED,
        REJECTED_USER,    // 사용자 동시 분석 제한 초과
        REJECTED_GLOBAL   // 시스템 전체 동시 분석 제한 초과
    }
}
