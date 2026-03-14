package com.neul.core_api.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import com.neul.core_api.domain.chat.entity.HighlightRecord;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import com.neul.core_api.domain.chat.repository.HighlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

        private final AnalyzedChatRepository analyzedChatRepository;
        private final HighlightRepository highlightRepository;
        private final StreamRedisService streamRedisService;
        private final ObjectMapper objectMapper;
        private final WebClient chzzkWebClient = WebClient.builder().build();

        // Room(channelId)별 SSE Sink 맵 (replay 최근 100개 버퍼)
        private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();

        // 하이라이트 감지를 위한 최근 고득점 감정 기록 (roomId -> LastPeak)
        private final Map<String, HighlightRecord> lastHighlights = new ConcurrentHashMap<>();

        /**
         * analyzed-chat-topic 소비.
         * CHAT → DB 저장 + Redis 통계 갱신 + SSE 푸시
         * DONATION / SUBSCRIPTION → SSE 패스스루만 (DB·Redis 저장 없음)
         */
        @KafkaListener(topics = "analyzed-chat-topic", groupId = "neul-core-api-group", containerFactory = "kafkaListenerContainerFactory")
        public void consumeAnalyzedChat(String json) {
                try {
                        AnalyzedChatMessage message = objectMapper.readValue(json, AnalyzedChatMessage.class);
                        String roomId = message.getRoomId();
                        String type = message.getMessageType() != null ? message.getMessageType() : "CHAT";

                        switch (type) {
                                case "CHAT" -> handleChat(roomId, message);
                                case "DONATION" -> handleDonation(roomId, message);
                                case "SUBSCRIPTION" -> handleSubscription(roomId, message);
                                default -> log.warn("[Kafka] Unknown messageType={} for roomId={}", type, roomId);
                        }
                } catch (JsonProcessingException e) {
                        log.error("[Kafka] Failed to parse AnalyzedChatMessage: {}", json, e);
                }
        }

        // ─── CHAT ────────────────────────────────────────────────────────────────

        private void handleChat(String roomId, AnalyzedChatMessage message) {
                log.info("[Kafka] CHAT: roomId={}, sender={}, emotion={}",
                                roomId, message.getSender(),
                                message.getEmotion() != null ? message.getEmotion().getType() : "N/A");

                // 1. PostgreSQL 비동기 저장
                AnalyzedChat entity = AnalyzedChat.builder()
                                .messageId(message.getMessageId())
                                .roomId(roomId)
                                .content(message.getContent())
                                .sender(message.getSender())
                                .emotionType(message.getEmotion() != null ? message.getEmotion().getType() : null)
                                .emotionScore(message.getEmotion() != null ? message.getEmotion().getScore() : null)
                                .analyzedAt(message.getAnalyzedAt())
                                .build();

                analyzedChatRepository.save(entity)
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                                saved -> log.debug("Saved CHAT to DB: id={}", saved.getId()),
                                                error -> log.error("DB Save Error: {}", error.getMessage()));

                // 2. Redis 통계 갱신 + SSE 이벤트 발행
                if (message.getEmotion() != null) {
                        String emotion = message.getEmotion().getType();
                        double score = message.getEmotion().getScore();

                        streamRedisService.incrementEmotionStats(roomId, emotion)
                                        .then(streamRedisService.getRoomStats(roomId))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                        stats -> {
                                                                Sinks.Many<Object> sink = roomSinks.get(roomId);
                                                                if (sink != null) {
                                                                        sink.tryEmitNext(Map.of("event", "chat_analyzed", "data", message));
                                                                        sink.tryEmitNext(Map.of("event", "stats_update", "data", stats));
                                                                        
                                                                        // 하이라이트 감지 로직 (임계값 0.8 이상)
                                                                        if (!"NEUTRAL".equals(emotion) && score >= 0.8) {
                                                                                detectAndEmitHighlight(roomId, message);
                                                                        }
                                                                }
                                                        },
                                                        error -> log.error("Redis Update Error: {}",
                                                                        error.getMessage()));
                }
        }

        private void detectAndEmitHighlight(String roomId, AnalyzedChatMessage message) {
                // 최근 10초 내에 동일 감정 하이라이트가 있었다면 스킵 (중복 방지)
                HighlightRecord last = lastHighlights.get(roomId);
                if (last != null && last.getTimestamp().isAfter(LocalDateTime.now().minusSeconds(10))) {
                        return;
                }

                log.info("[Highlight] Spike detected! roomId={}, emotion={}, score={}", 
                        roomId, message.getEmotion().getType(), message.getEmotion().getScore());

                // Chzzk에서 현재 실시간 썸네일 URL 가져오기
                chzzkWebClient.get()
                        .uri("https://api.chzzk.naver.com/service/v2/channels/" + roomId + "/live-status")
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .map(node -> node.path("content").path("liveImageUrl").asText())
                        .flatMap(imageUrl -> {
                                HighlightRecord highlight = HighlightRecord.builder()
                                        .roomId(roomId)
                                        .emotionType(message.getEmotion().getType())
                                        .peakScore(message.getEmotion().getScore())
                                        .topMessage(message.getContent())
                                        .liveImageUrl(imageUrl.replace("{type}", "1080")) // 고화질
                                        .timestamp(LocalDateTime.now())
                                        .build();
                                
                                return highlightRepository.save(highlight);
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(saved -> {
                                lastHighlights.put(roomId, saved);
                                Sinks.Many<Object> sink = roomSinks.get(roomId);
                                if (sink != null) {
                                        sink.tryEmitNext(Map.of("event", "highlight_detected", "data", saved));
                                }
                        }, err -> log.error("Highlight Process Error: {}", err.getMessage()));
        }

        // ─── DONATION ────────────────────────────────────────────────────────────

        private void handleDonation(String roomId, AnalyzedChatMessage message) {
                log.info("[Kafka] DONATION: roomId={}, from={}, amount={}원",
                                roomId, message.getDonatorNickname(), message.getPayAmount());

                Sinks.Many<Object> sink = roomSinks.get(roomId);
                if (sink != null) {
                        sink.tryEmitNext(Map.of("event", "donation", "data", message));
                }
        }

        // ─── SUBSCRIPTION ────────────────────────────────────────────────────────

        private void handleSubscription(String roomId, AnalyzedChatMessage message) {
                log.info("[Kafka] SUBSCRIPTION: roomId={}, from={}, tier{} {}개월",
                                roomId, message.getSubscriberNickname(), message.getTierNo(), message.getMonth());

                Sinks.Many<Object> sink = roomSinks.get(roomId);
                if (sink != null) {
                        sink.tryEmitNext(Map.of("event", "subscription", "data", message));
                }
        }

        // ─── SSE 구독 ─────────────────────────────────────────────────────────────

        public Flux<Object> subscribeRoom(String roomId) {
                log.info("Client subscribed to SSE stream for room: {}", roomId);

                Sinks.Many<Object> sink = roomSinks.computeIfAbsent(roomId,
                                key -> Sinks.many().replay().limit(100));

                return sink.asFlux()
                                .doOnCancel(() -> log.info("Client unsubscribed from room: {}", roomId))
                                .doFinally(signalType -> {
                                        if (sink.currentSubscriberCount() == 0) {
                                                roomSinks.remove(roomId);
                                                log.info("Removed unused sink for room: {}", roomId);
                                        }
                                });
        }
}
