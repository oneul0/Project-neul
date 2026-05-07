package com.gak.core_api.domain.live.controller;

import com.gak.core_api.domain.live.service.ChzzkLiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 라이브 채널 목록 조회 API.
 *
 * GET /api/v1/lives → 라이브 채널 목록 (감정 통계 포함)
 * GET /api/v1/lives/{channelId} → 특정 채널 라이브 상태 + 감정 통계
 */
@RestController
@RequestMapping("/api/v1/lives")
@RequiredArgsConstructor
public class LiveController {

    private final ChzzkLiveService liveService;

    /**
     * 현재 라이브 중인 채널 목록 조회 (감정 점수 포함).
     * 프론트엔드 홈 화면 - 스트리머 탐색 카드 목록에 사용.
     *
     * @param size 조회 개수 (1~20, 기본 20)
     * @param next 다음 페이지 커서
     */
    @GetMapping
    public Mono<Map<String, Object>> getLives(
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String next) {

        int safeSize = Math.min(Math.max(size, 1), 20);
        return liveService.getLivesWithSentiment(safeSize, next);
    }
}
