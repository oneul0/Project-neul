package com.gak.core_api.domain.chat.controller;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import com.gak.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.gak.core_api.domain.chat.service.OwnerIdentityResolver;
import com.gak.core_api.domain.chat.service.VodAnalysisOrchestrator;
import com.gak.core_api.domain.chat.service.VodHighlightQueryService;
import com.gak.core_api.domain.chat.service.VodTimelineQueryService;
import com.gak.core_api.domain.chat.service.dto.TriggerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * VOD 하이라이트/타임라인/분석 HTTP 어댑터.
 * HTTP adapter: request validation → service call → HTTP mapping. No business logic.
 *
 * <p>슬롯 가드레일 설계: {@code docs/design/14_vod_concurrency_plan.md}
 * <p>분석 시퀀스: {@code docs/design/26_vod_highlight_sequence_diagrams.md}
 */
@RestController
@RequestMapping("/api/v1/vod")
@RequiredArgsConstructor
@Slf4j
public class VodController {

    private final OwnerIdentityResolver ownerIdentityResolver;
    private final VodHighlightQueryService highlightQueryService;
    private final VodTimelineQueryService timelineQueryService;
    private final VodAnalysisOrchestrator analysisOrchestrator;

    @GetMapping("/{videoNo}/highlights")
    public Flux<VodHighlight> getHighlights(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);
        return highlightQueryService.getPersonalizedHighlights(ownerId, videoNo);
    }

    @GetMapping("/{videoNo}/timeline")
    public Flux<VodTimelinePointEntity> getTimeline(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);
        return timelineQueryService.getTimelinePoints(ownerId, videoNo);
    }

    /**
     * VOD 분석 시작. 슬롯 가드레일로 사용자/시스템 동시 분석 수를 제한.
     *
     * <ul>
     *   <li>200 OK: 분석 파이프라인 시작 (ACQUIRED)
     *   <li>429 Too Many Requests: 해당 사용자가 이미 분석 중
     *   <li>503 Service Unavailable: 시스템 전체 분석 슬롯 소진
     *   <li>500 Internal Server Error: 파이프라인 실행 중 오류
     * </ul>
     */
    @PostMapping("/{videoNo}/analyze")
    public Mono<ResponseEntity<String>> triggerAnalysis(
            @PathVariable String videoNo,
            ServerWebExchange exchange
    ) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(exchange);
        return analysisOrchestrator.trigger(videoNo, ownerId)
                .map(this::toHttpResponse);
    }

    private ResponseEntity<String> toHttpResponse(TriggerResult result) {
        if (result instanceof TriggerResult.Accepted a) {
            return ResponseEntity.ok(a.message());
        }
        if (result instanceof TriggerResult.RejectedUser) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .<String>body("이미 분석 중인 VOD가 있습니다. 완료 후 다시 시도해주세요.");
        }
        if (result instanceof TriggerResult.RejectedGlobal) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .<String>body("현재 분석 요청이 많습니다. 잠시 후 다시 시도해주세요.");
        }
        // TriggerResult.Failed — 상세 원인은 Orchestrator에서 로깅
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .<String>body("분석 요청 처리 중 오류가 발생했습니다.");
    }
}
