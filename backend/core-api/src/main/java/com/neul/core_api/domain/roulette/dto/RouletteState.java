package com.neul.core_api.domain.roulette.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 현재 룰렛 상태 스냅샷.
 *
 * <p>{@code weights}는 유효 가중치(베이스 1.0 + 누적 도네이션 가중치)이며,
 * {@code probabilities}는 각 항목의 당첨 확률(0.0 ~ 1.0)입니다.</p>
 */
@Getter
@Builder
public class RouletteState {
    private List<String> items;
    private int rate;
    private Map<String, Double> weights;       // base 1.0 + donation contribution
    private Map<String, Double> probabilities; // weight_i / totalWeight
    private double totalWeight;
}
