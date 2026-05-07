package com.gak.core_api.domain.chat.controller;

import com.gak.core_api.domain.chat.entity.AnalyzedChat;
import com.gak.core_api.domain.chat.repository.AnalyzedChatRepository;
import com.gak.core_api.domain.chat.service.StreamRedisService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/poll")
@RequiredArgsConstructor
public class PollController {

    private final StreamRedisService streamRedisService;
    private final AnalyzedChatRepository analyzedChatRepository;

    /**
     * 세션 수집 상태 변경 (스트리머 전용)
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
     * 투표 결과 요약 조회 — {라벨: 득표수} 형식으로 반환합니다.
     * 시청자가 "!1", "!2" 형태로 투표하면 항목 순서(1-indexed)로 라벨에 매핑합니다.
     */
    @GetMapping("/{roomId}/results")
    public Mono<Map<String, Long>> getPollResults(@PathVariable String roomId) {
        return Mono.zip(
                streamRedisService.getPollItems(roomId),
                streamRedisService.getPollResults(roomId)
        ).map(tuple -> {
            List<String> items = tuple.getT1();
            Map<Object, Object> rawVotes = tuple.getT2();

            // 항목별 초기 0 카운트
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String item : items) {
                counts.put(item, 0L);
            }

            // 투표 집계 — 숫자를 라벨로 변환
            for (Object rawOption : rawVotes.values()) {
                String label = resolveLabel(rawOption.toString(), items);
                if (label != null) {
                    counts.merge(label, 1L, Long::sum);
                }
            }

            return counts;
        });
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
     * 투표자 목록 조회 — {닉네임(또는 userId): 선택한 라벨} 형식으로 반환합니다.
     */
    @GetMapping("/{roomId}/voters")
    public Mono<Map<String, String>> getVoters(@PathVariable String roomId) {
        return Mono.zip(
                streamRedisService.getPollItems(roomId),
                streamRedisService.getPollResults(roomId),
                streamRedisService.getVoterNames(roomId)
        ).map(tuple -> {
            List<String> items = tuple.getT1();
            Map<Object, Object> rawVotes = tuple.getT2();
            Map<Object, Object> voterNames = tuple.getT3();

            Map<String, String> result = new HashMap<>();
            rawVotes.forEach((userId, rawOption) -> {
                String label = resolveLabel(rawOption.toString(), items);
                if (label != null) {
                    // 가능하면 displayName으로, 없으면 userId로 표시
                    String displayKey = voterNames.getOrDefault(userId, userId).toString();
                    result.put(displayKey, label);
                }
            });

            return result;
        });
    }

    /**
     * 특정 사용자의 이번 세션 채팅 기록 조회
     */
    @GetMapping("/{roomId}/voters/{userId}/history")
    public reactor.core.publisher.Flux<AnalyzedChat> getVoterChatHistory(
            @PathVariable String roomId,
            @PathVariable String userId) {
        // userId에는 senderId 또는 displayName이 올 수 있으므로 양쪽 모두 조회
        return analyzedChatRepository.findByRoomIdAndSenderId(roomId, userId)
                .switchIfEmpty(analyzedChatRepository.findByRoomIdAndSender(roomId, userId));
    }

    /**
     * 투표 항목 설정
     */
    @PostMapping("/{roomId}/items")
    public Mono<Boolean> setPollItems(@PathVariable String roomId,
            @RequestBody @Size(min = 2, max = 20) List<@Size(min = 1, max = 50) String> items) {
        log.info("[Poll] Setting poll items for room {}: {}", roomId, items);
        return streamRedisService.setPollItems(roomId, items);
    }

    /**
     * 투표 항목 조회
     */
    @GetMapping("/{roomId}/items")
    public Mono<List<String>> getPollItems(@PathVariable String roomId) {
        return streamRedisService.getPollItems(roomId);
    }

    // ─── 내부 유틸 ──────────────────────────────────────────────────────────────

    /**
     * 원시 투표 옵션 문자열을 항목 라벨로 변환합니다.
     *
     * <ul>
     *   <li>"1", "2", ... → 1-indexed 항목 라벨 (시청자가 "!1"로 투표한 경우)</li>
     *   <li>이미 라벨 문자열인 경우 그대로 반환</li>
     *   <li>유효하지 않은 값은 null 반환 (집계 제외)</li>
     * </ul>
     */
    private static String resolveLabel(String rawOption, List<String> items) {
        if (items.isEmpty()) {
            return rawOption; // 항목 미설정 시 원본 값 사용
        }
        try {
            int idx = Integer.parseInt(rawOption) - 1; // "1" → index 0
            if (idx >= 0 && idx < items.size()) {
                return items.get(idx);
            }
        } catch (NumberFormatException ignored) {
            // 숫자가 아닌 경우 — 라벨 직접 매칭 시도
        }
        return items.contains(rawOption) ? rawOption : null;
    }
}
