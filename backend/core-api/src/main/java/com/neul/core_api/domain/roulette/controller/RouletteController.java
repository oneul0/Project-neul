package com.neul.core_api.domain.roulette.controller;

import com.neul.core_api.domain.roulette.dto.RouletteConfigRequest;
import com.neul.core_api.domain.roulette.dto.RouletteResult;
import com.neul.core_api.domain.roulette.dto.RouletteState;
import com.neul.core_api.domain.roulette.service.RouletteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 룰렛 REST API.
 *
 * <pre>
 * GET    /api/v1/roulette/{channelId}          — 현재 상태 (항목, 가중치, 확률)
 * PUT    /api/v1/roulette/{channelId}/config    — 항목·배율 설정 (owner)
 * POST   /api/v1/roulette/{channelId}/spin      — 룰렛 돌리기 (owner)
 * DELETE /api/v1/roulette/{channelId}/weights   — 도네이션 가중치 초기화 (owner)
 * DELETE /api/v1/roulette/{channelId}           — 전체 초기화 (owner)
 * </pre>
 *
 * GET 엔드포인트는 공개 조회 목적으로 인증 불필요.
 * 나머지는 {@link com.neul.core_api.config.OwnerAccessFilter}가 보호합니다.
 */
@RestController
@RequestMapping("/api/v1/roulette")
@RequiredArgsConstructor
public class RouletteController {

    private final RouletteService rouletteService;

    @GetMapping("/{channelId}")
    public Mono<RouletteState> getState(@PathVariable String channelId) {
        return rouletteService.getState(channelId);
    }

    @PutMapping("/{channelId}/config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> setConfig(
            @PathVariable String channelId,
            @RequestBody RouletteConfigRequest request) {
        return rouletteService.setConfig(channelId, request);
    }

    @PostMapping("/{channelId}/spin")
    public Mono<RouletteResult> spin(@PathVariable String channelId) {
        return rouletteService.spin(channelId);
    }

    @DeleteMapping("/{channelId}/weights")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> resetWeights(@PathVariable String channelId) {
        return rouletteService.resetWeights(channelId);
    }

    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> clearAll(@PathVariable String channelId) {
        return rouletteService.clearAll(channelId);
    }
}
