package com.neul.collector.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neul.collector.service.ChatCollector;
import com.neul.collector.service.NidChatCollector.AdultStreamException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
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
        private final WebClient chzzkWebClient;

        @GetMapping("/{channelId}/status")
        public Mono<ResponseEntity<Map<String, Object>>> getChannelStatus(@PathVariable String channelId) {
                return chzzkWebClient.get()
                                .uri("https://api.chzzk.naver.com/polling/v2/channels/" + channelId + "/live-status")
                                .exchangeToMono(response -> {
                                        if (response.statusCode().is2xxSuccessful()) {
                                                return response.bodyToMono(ObjectNode.class)
                                                                .map(node -> ResponseEntity.ok(buildStatusResponse(channelId, node)));
                                        }

                                        if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
                                                log.warn("[Chzzk] Channel {} was not found while fetching live status.", channelId);
                                                return Mono.just(buildStatusErrorResponse(
                                                                HttpStatus.NOT_FOUND,
                                                                channelId,
                                                                "존재하지 않거나 조회할 수 없는 채널입니다."));
                                        }

                                        return response.createException()
                                                        .flatMap(e -> {
                                                                log.error("[Chzzk] Failed to fetch live status for channel {}: {}", channelId,
                                                                                e.getMessage());
                                                                return Mono.just(buildStatusErrorResponse(
                                                                                HttpStatus.OK,
                                                                                channelId,
                                                                                e.getMessage()));
                                                        });
                                })
                                .onErrorResume(e -> {
                                        log.error("[Chzzk] Failed to fetch live status for channel {}: {}", channelId,
                                                        e.getMessage());
                                        return Mono.just(buildStatusErrorResponse(
                                                        HttpStatus.OK,
                                                        channelId,
                                                        e.getMessage()));
                                });
        }

        @PostMapping("/{channelId}/subscribe")
        public Mono<ResponseEntity<Map<String, Object>>> subscribe(@PathVariable String channelId) {
                if (chatCollector.isSubscribed(channelId)) {
                        return Mono.just(ResponseEntity.ok(buildResponse(channelId, "already_subscribed", null)));
                }

                return chatCollector.subscribe(channelId)
                                .thenReturn(ResponseEntity.ok(buildResponse(channelId, "subscribed", null)))
                                .onErrorResume(AdultStreamException.class, e -> {
                                        log.warn("[Chzzk] Adult stream detected for channel {}: {}", channelId, e.getMessage());
                                        Map<String, Object> body = new HashMap<>();
                                        body.put("channelId", channelId);
                                        body.put("error", e.isHasCredentials() ? "adult_stream_login_required" : "adult_stream_api_unconfigured");
                                        body.put("message", e.isHasCredentials()
                                                ? "성인 방송은 채널 소유자가 치지직 공식 로그인을 완료해야 수집할 수 있습니다."
                                                : "성인 방송 수집을 위한 API 설정이 되어 있지 않습니다.");
                                        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body));
                                })
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

        private Map<String, Object> buildStatusResponse(String channelId, ObjectNode node) {
                Map<String, Object> map = new HashMap<>();
                map.put("channelId", channelId);

                JsonNode content = node.path("content");
                boolean hasContent = !content.isMissingNode() && !content.isNull();
                String chatChannelId = hasContent ? content.path("chatChannelId").asText("") : "";
                boolean live = !chatChannelId.isBlank();

                map.put("live", live);
                map.put("status", live ? "live" : "offline");
                map.put("chatChannelId", chatChannelId);

                if (hasContent) {
                        String liveTitle = content.path("liveTitle").asText("");
                        if (!liveTitle.isBlank()) {
                                map.put("liveTitle", liveTitle);
                        }

                        String status = content.path("status").asText("");
                        if (!status.isBlank()) {
                                map.put("rawStatus", status);
                        }

                        int concurrentUserCount = content.path("concurrentUserCount").asInt(-1);
                        if (concurrentUserCount >= 0) {
                                map.put("viewerCount", concurrentUserCount);
                        }
                }

                if (!live) {
                        map.put("message", "현재 라이브 상태가 아니어서 채팅 분석을 시작할 수 없습니다.");
                }

                return map;
        }

        private ResponseEntity<Map<String, Object>> buildStatusErrorResponse(HttpStatus status, String channelId,
                        String message) {
                Map<String, Object> body = new HashMap<>();
                body.put("channelId", channelId);
                body.put("live", false);
                body.put("status", "failed");
                body.put("message", message);
                return ResponseEntity.status(status).body(body);
        }
}
