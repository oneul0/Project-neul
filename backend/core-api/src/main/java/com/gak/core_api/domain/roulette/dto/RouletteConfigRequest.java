package com.gak.core_api.domain.roulette.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 룰렛 항목 및 배율 설정 요청.
 *
 * @param items 항목 이름 목록 (순서 보존, 중복 불허)
 * @param rate  가중치 1당 원화 금액 (e.g. 1000 → 1,000원 = 가중치 1.0 추가)
 */
public record RouletteConfigRequest(
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Size(min = 1, max = 50) String> items,
        @Min(1) @Max(1_000_000) int rate
) {}
