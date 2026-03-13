package com.neul.collector.controller;

import com.neul.collector.service.ChatCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Chzzk 채널 구독 제어 컨트롤러.
 *
 * POST /api/v1/channels/{channelId}/subscribe → Chzzk Socket.IO 연결 + 이벤트 구독 시작
 * DELETE /api/v1/channels/{channelId}/subscribe → 구독 취소 및 연결 해제
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/channels")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
@RequiredArgsConstructor
public class ChzzkChannelController {

        private final ChatCollector chatCollector;

        @PostMapping("/{channelId}/subscribe")
        public Mono<ResponseEntity<Map<String, Object>>> subscribe(@PathVariable String channelId) {
                if (chatCollector.isSubscribed(channelId)) {
                        return Mono.just(ResponseEntity.ok(buildResponse(channelId, "already_subscribed", null)));
                }

                return chatCollector.subscribe(channelId)
                                .thenReturn(ResponseEntity.ok(buildResponse(channelId, "subscribed", null)))
                                .onErrorResume(e -> {
                                        log.error("[Chzzk] Subscribe failed for channel {}: {}", channelId,
                                                        e.getMessage());
                                        return Mono.just(ResponseEntity
                                                        .internalServerError()
                                                        .body(buildResponse(channelId, "failed", e.getMessage())));
                                });
        }

        @DeleteMapping("/{channelId}/subscribe")
        public Mono<ResponseEntity<Map<String, Object>>> unsubscribe(@PathVariable String channelId) {
                return chatCollector.unsubscribe(channelId)
                                .thenReturn(ResponseEntity.ok(buildResponse(channelId, "unsubscribed", null)))
                                .onErrorResume(e -> {
                                        log.error("[Chzzk] Unsubscribe failed for channel {}: {}", channelId,
                                                        e.getMessage());
                                        return Mono.just(ResponseEntity
                                                        .internalServerError()
                                                        .body(buildResponse(channelId, "failed", e.getMessage())));
                                });
        }

        private Map<String, Object> buildResponse(String channelId, String status, String error) {
                Map<String, Object> map = new HashMap<>();
                map.put("channelId", channelId);
                map.put("status", status);
                if (error != null) {
                        map.put("error", error);
                }
                return map;
        }
}
