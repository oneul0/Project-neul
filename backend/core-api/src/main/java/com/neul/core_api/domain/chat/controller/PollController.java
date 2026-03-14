package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import com.neul.core_api.domain.chat.service.StreamRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/poll")
@RequiredArgsConstructor
public class PollController {

    private final StreamRedisService streamRedisService;
    private final AnalyzedChatRepository analyzedChatRepository;

    /**
     * 세션 수집 상태 변경 (스트리머 전용 - 현재는 단순 시뮬레이션)
     */
    @PostMapping("/{roomId}/session")
    public Mono<Boolean> toggleSession(@PathVariable String roomId, @RequestParam boolean active) {
        log.info("[Session] Setting room {} session active={}", roomId, active);
        return streamRedisService.setCollectionActive(roomId, active);
    }

    /**
     * 현재 세션 수집 상태 조회
     */
    @GetMapping("/{roomId}/session")
    public Mono<Boolean> isSessionActive(@PathVariable String roomId) {
        return streamRedisService.isCollectionActive(roomId);
    }

    /**
     * 투표 결과 요약 조회
     */
    @GetMapping("/{roomId}/results")
    public Mono<Map<String, Long>> getPollResults(@PathVariable String roomId) {
        return streamRedisService.getPollResults(roomId)
                .map(votes -> votes.values().stream()
                        .collect(Collectors.groupingBy(Object::toString, Collectors.counting())));
    }

    /**
     * 투표함 초기화 (새 투표 시작 시)
     */
    @DeleteMapping("/{roomId}")
    public Mono<Void> clearPoll(@PathVariable String roomId) {
        log.info("[Poll] Clearing poll for room {}", roomId);
        return streamRedisService.clearPoll(roomId);
    }

    /**
     * 특정 선택지에 투표한 명단 조회 (Phase 23)
     */
    @GetMapping("/{roomId}/voters")
    public Mono<Map<String, String>> getVoters(@PathVariable String roomId) {
        // userId -> option
        return streamRedisService.getPollResults(roomId)
                .map(votes -> votes.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                e -> e.getValue().toString()
                        )));
    }

    /**
     * 특정 사용자의 이번 세션 채팅 기록 조회 (Phase 23)
     */
    @GetMapping("/{roomId}/voters/{userId}/history")
    public reactor.core.publisher.Flux<AnalyzedChat> getVoterChatHistory(@PathVariable String roomId, @PathVariable String userId) {
        return analyzedChatRepository.findByRoomIdAndSenderId(roomId, userId);
    }

    /**
     * 투표 항목 설정 (Phase 24)
     */
    @PostMapping("/{roomId}/items")
    public Mono<Boolean> setPollItems(@PathVariable String roomId, @RequestBody java.util.List<String> items) {
        log.info("[Poll] Setting poll items for room {}: {}", roomId, items);
        return streamRedisService.setPollItems(roomId, items);
    }

    /**
     * 투표 항목 조회 (Phase 24)
     */
    @GetMapping("/{roomId}/items")
    public Mono<java.util.List<String>> getPollItems(@PathVariable String roomId) {
        return streamRedisService.getPollItems(roomId);
    }
}
