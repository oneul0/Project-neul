package com.neul.core_api.domain.chat.controller;

import com.neul.common.dto.VoteRequest;
import com.neul.core_api.domain.chat.service.VoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 투표(Vote) 및 키워드 수집 제어 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/votes")
@RequiredArgsConstructor
public class VoteController {

    private final VoteService voteService;

    @PostMapping("/{roomId}/start")
    public Mono<ResponseEntity<Map<String, Object>>> startVote(
            @PathVariable String roomId,
            @RequestBody VoteRequest request) {
        request.setRoomId(roomId);
        request.setActive(true);
        
        return voteService.startVote(request)
                .thenReturn(ResponseEntity.ok(Map.of(
                        "status", "success",
                        "roomId", roomId,
                        "message", "Vote and keyword collection started"
                )));
    }

    @PostMapping("/{roomId}/stop")
    public Mono<ResponseEntity<Map<String, Object>>> stopVote(@PathVariable String roomId) {
        return voteService.stopVote(roomId)
                .thenReturn(ResponseEntity.ok(Map.of(
                        "status", "success",
                        "roomId", roomId,
                        "message", "Vote and keyword collection stopped"
                )));
    }

    @GetMapping("/{roomId}/keywords")
    public Mono<ResponseEntity<Map<String, Object>>> getKeywordStats(@PathVariable String roomId) {
        return voteService.getKeywords(roomId)
                .map(stats -> ResponseEntity.ok(Map.of(
                        "roomId", roomId,
                        "keywords", stats
                )));
    }
}
