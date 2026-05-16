package com.gak.core_api.domain.roulette.service;

import com.gak.core_api.domain.roulette.dto.RouletteConfigRequest;
import com.gak.core_api.domain.roulette.dto.RouletteResult;
import com.gak.core_api.domain.roulette.dto.RouletteState;
import com.gak.core_api.domain.roulette.strategy.RouletteStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 룰렛 비즈니스 로직.
 *
 * <p>스핀 알고리즘은 {@link RouletteStrategy}에 위임합니다.
 * 알고리즘 교체 시 이 클래스를 수정하지 않고 전략 구현체만 교체하면 됩니다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouletteService {

    private final RouletteRedisService redisService;
    private final RouletteStrategy strategy;

    // ─── 설정 ──────────────────────────────────────────────────────────────────

    public Mono<Void> setConfig(String channelId, RouletteConfigRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "항목이 비어 있습니다."));
        }
        if (req.rate() <= 0) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "배율(rate)은 1 이상이어야 합니다."));
        }
        return redisService.setConfig(channelId, req.items(), req.rate());
    }

    // ─── 상태 조회 ─────────────────────────────────────────────────────────────

    public Mono<RouletteState> getState(String channelId) {
        return buildState(channelId);
    }

    // ─── 스핀 ──────────────────────────────────────────────────────────────────

    public Mono<RouletteResult> spin(String channelId) {
        return buildState(channelId).flatMap(state -> {
            if (state.getItems().isEmpty()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "항목을 먼저 설정해 주세요."));
            }
            String winner = strategy.spin(state.getItems(), state.getWeights());
            double prob = state.getProbabilities().getOrDefault(winner, 0.0);
            log.info("[Roulette] spin channelId={} winner={} prob={:.2f}", channelId, winner, prob);
            return Mono.just(RouletteResult.builder()
                    .winner(winner)
                    .probability(prob)
                    .build());
        });
    }

    // ─── 초기화 ────────────────────────────────────────────────────────────────

    public Mono<Void> resetWeights(String channelId) {
        return redisService.resetWeights(channelId);
    }

    public Mono<Void> clearAll(String channelId) {
        return redisService.clearAll(channelId);
    }

    // ─── 도네이션 연동 ─────────────────────────────────────────────────────────

    /**
     * 도네이션 메시지와 금액을 받아 항목 가중치를 업데이트합니다.
     *
     * <p>메시지에서 항목 이름을 찾지 못하면 아무 작업도 수행하지 않습니다.
     * 항목이 아직 설정되지 않은 경우에도 조용히 종료합니다.</p>
     *
     * @param channelId  채널 ID
     * @param message    도네이션 메시지 (null 허용)
     * @param amountKrw  도네이션 금액 (원)
     */
    public Mono<Void> onDonation(String channelId, String message, long amountKrw) {
        return Mono.zip(
                redisService.getItems(channelId),
                redisService.getRate(channelId)
        ).flatMap(tuple -> {
            List<String> items = tuple.getT1();
            int rate = tuple.getT2();
            if (items.isEmpty() || rate <= 0 || amountKrw <= 0) return Mono.empty();

            Optional<String> matched = matchItem(items, message);
            if (matched.isEmpty()) return Mono.empty();

            double increment = (double) amountKrw / rate;
            log.debug("[Roulette] onDonation channelId={} item={} +{}", channelId, matched.get(), increment);
            return redisService.addDonationWeight(channelId, matched.get(), increment).then();
        });
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private Mono<RouletteState> buildState(String channelId) {
        return Mono.zip(
                redisService.getItems(channelId),
                redisService.getRate(channelId),
                redisService.getDonationWeights(channelId)
        ).map(tuple -> {
            List<String> items = tuple.getT1();
            int rate = tuple.getT2();
            Map<String, Double> donationWeights = tuple.getT3();

            // 유효 가중치 = 베이스 1.0 + 누적 도네이션 가중치
            Map<String, Double> weights = new LinkedHashMap<>();
            for (String item : items) {
                weights.put(item, 1.0 + donationWeights.getOrDefault(item, 0.0));
            }

            double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();

            Map<String, Double> probabilities = new LinkedHashMap<>();
            if (totalWeight > 0) {
                weights.forEach((item, w) -> probabilities.put(item, w / totalWeight));
            }

            return RouletteState.builder()
                    .items(items)
                    .rate(rate)
                    .weights(weights)
                    .probabilities(probabilities)
                    .totalWeight(totalWeight)
                    .build();
        });
    }

    /**
     * 도네이션 메시지에서 항목 이름을 찾습니다 (포함 여부, 대소문자 무시).
     * 여러 항목이 메시지에 포함된 경우 items 목록의 첫 번째 항목을 반환합니다.
     */
    private Optional<String> matchItem(List<String> items, String message) {
        if (message == null) return Optional.empty();
        String lower = message.strip().toLowerCase();
        return items.stream()
                .filter(item -> lower.contains(item.toLowerCase()))
                .findFirst();
    }
}
