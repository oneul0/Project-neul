package com.neul.core_api.domain.roulette.dto;

import java.util.List;

/**
 * 룰렛 항목 및 배율 설정 요청.
 *
 * @param items 항목 이름 목록 (순서 보존, 중복 불허)
 * @param rate  가중치 1당 원화 금액 (e.g. 1000 → 1,000원 = 가중치 1.0 추가)
 */
public record RouletteConfigRequest(List<String> items, int rate) {}
