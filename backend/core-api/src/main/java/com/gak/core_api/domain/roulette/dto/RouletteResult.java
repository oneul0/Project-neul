package com.gak.core_api.domain.roulette.dto;

import lombok.Builder;
import lombok.Getter;

/** 룰렛 스핀 결과. */
@Getter
@Builder
public class RouletteResult {
    private String winner;      // 당첨된 항목 이름
    private double probability; // 스핀 시점의 당첨 확률 (0.0 ~ 1.0)
}
