package com.gak.core_api.domain.roulette.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 룰렛 데이터 Redis 영속 계층.
 *
 * <p>Redis 키 구조:
 * <pre>
 *   roulette:{channelId}:items     — List&lt;String&gt;  항목 이름 목록 (순서 보존)
 *   roulette:{channelId}:rate      — String         1 가중치당 원화 금액
 *   roulette:{channelId}:donations — Hash           항목별 누적 도네이션 가중치 (HINCRBYFLOAT)
 * </pre>
 * donations 해시에는 베이스 가중치(1.0)를 저장하지 않습니다.
 * 유효 가중치 = 1.0 + donations[item] 은 서비스 계층에서 계산합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class RouletteRedisService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    private static final String KEY_ITEMS     = "roulette:%s:items";
    private static final String KEY_RATE      = "roulette:%s:rate";
    private static final String KEY_DONATIONS = "roulette:%s:donations";

    // ─── 설정 ──────────────────────────────────────────────────────────────────

    public Mono<Void> setConfig(String channelId, List<String> items, int rate) {
        String itemKey = String.format(KEY_ITEMS, channelId);
        String rateKey = String.format(KEY_RATE, channelId);
        Mono<Void> saveItems = redisTemplate.delete(itemKey)
                .then(items.isEmpty()
                        ? Mono.empty()
                        : redisTemplate.opsForList().rightPushAll(itemKey, items.toArray()).then());
        Mono<Void> saveRate = redisTemplate.opsForValue().set(rateKey, rate).then();
        return Mono.when(saveItems, saveRate);
    }

    public Mono<List<String>> getItems(String channelId) {
        return redisTemplate.opsForList()
                .range(String.format(KEY_ITEMS, channelId), 0, -1)
                .map(Object::toString)
                .collectList();
    }

    public Mono<Integer> getRate(String channelId) {
        return redisTemplate.opsForValue()
                .get(String.format(KEY_RATE, channelId))
                .map(val -> {
                    try { return Integer.parseInt(val.toString()); }
                    catch (NumberFormatException e) { return 1000; }
                })
                .defaultIfEmpty(1000);
    }

    // ─── 도네이션 가중치 ───────────────────────────────────────────────────────

    /**
     * 특정 항목의 누적 도네이션 가중치를 원자적으로 증가합니다 (HINCRBYFLOAT).
     *
     * @return 증가 후의 새 누적 가중치 값
     */
    public Mono<Double> addDonationWeight(String channelId, String item, double increment) {
        return redisTemplate.opsForHash()
                .increment(String.format(KEY_DONATIONS, channelId), item, increment);
    }

    public Mono<Map<String, Double>> getDonationWeights(String channelId) {
        return redisTemplate.opsForHash()
                .entries(String.format(KEY_DONATIONS, channelId))
                .collectMap(
                        e -> e.getKey().toString(),
                        e -> {
                            try { return Double.parseDouble(e.getValue().toString()); }
                            catch (NumberFormatException ex) { return 0.0; }
                        });
    }

    // ─── 초기화 ────────────────────────────────────────────────────────────────

    /** 도네이션 가중치만 초기화합니다. 항목·배율 설정은 유지됩니다. */
    public Mono<Void> resetWeights(String channelId) {
        return redisTemplate.delete(String.format(KEY_DONATIONS, channelId)).then();
    }

    /** 항목·배율·도네이션 가중치를 모두 삭제합니다. */
    public Mono<Void> clearAll(String channelId) {
        return redisTemplate.delete(
                String.format(KEY_ITEMS, channelId),
                String.format(KEY_RATE, channelId),
                String.format(KEY_DONATIONS, channelId)).then();
    }
}
