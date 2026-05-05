package com.neul.core_api.domain.roulette.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 가중치 누적 선형 탐색 기반 룰렛 전략.
 *
 * <p>알고리즘:<br>
 * 1. 유효 가중치 배열에서 누적 가중치(prefix sum)를 계산합니다.<br>
 * 2. [0, totalWeight) 구간의 균등 분포 난수 r을 생성합니다.<br>
 * 3. r이 처음으로 누적 가중치를 하회하는 항목을 선택합니다.<br>
 * <br>
 * double(64-bit) 부동소수점을 사용하므로 누적 가중치 합이 ~10^12 이내일 때 오차 무시 가능.
 * 정수 슬롯 분배 방식과 달리 소수 가중치에서도 오차 없이 확률을 반영합니다.</p>
 */
@Component
public class WeightedLinearRouletteStrategy implements RouletteStrategy {

    @Override
    public String spin(List<String> items, Map<String, Double> weights) {
        if (items.isEmpty()) throw new IllegalArgumentException("항목이 비어 있습니다.");

        double totalWeight = 0.0;
        double[] cumulative = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            totalWeight += weights.getOrDefault(items.get(i), 1.0);
            cumulative[i] = totalWeight;
        }

        double r = ThreadLocalRandom.current().nextDouble() * totalWeight;
        for (int i = 0; i < items.size(); i++) {
            if (r < cumulative[i]) return items.get(i);
        }
        // floating-point rounding fallback: r가 totalWeight에 매우 근접한 경우
        return items.get(items.size() - 1);
    }
}
