package com.neul.core_api.domain.roulette.strategy;

import java.util.List;
import java.util.Map;

/**
 * 룰렛 당첨 항목 선택 전략 인터페이스.
 *
 * <p>가중치 기반 선택 알고리즘을 교체할 수 있도록 캡슐화합니다.
 * 기본 구현: {@link WeightedLinearRouletteStrategy} (double 누적 가중치 선형 탐색)</p>
 */
public interface RouletteStrategy {

    /**
     * 항목 목록과 유효 가중치(베이스 1.0 + 도네이션 가중치)를 받아 당첨 항목을 반환합니다.
     *
     * @param items   항목 이름 목록 (순서 보존)
     * @param weights 항목별 유효 가중치 map (key = 항목명, value ≥ 1.0)
     * @return 당첨된 항목 이름
     * @throws IllegalArgumentException items가 비어있는 경우
     */
    String spin(List<String> items, Map<String, Double> weights);
}
