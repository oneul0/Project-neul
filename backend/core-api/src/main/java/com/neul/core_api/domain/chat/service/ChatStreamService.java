package com.neul.core_api.domain.chat.service;

import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

        private final AnalyzedChatRepository analyzedChatRepository;
        private final StreamRedisService streamRedisService;

        // Room(channelId)별 SSE Sink 맵 (replay 최근 100개 버퍼)
        private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();

        /**
         * analyzed-chat-topic 소비.
         * CHAT → DB 저장 + Redis 통계 갱신 + SSE 푸시
         * DONATION / SUBSCRIPTION → SSE 패스스루만 (DB·Redis 저장 없음)
         */
        @KafkaListener(topics = "analyzed-chat-topic", groupId = "neul-core-api-group", containerFactory = "kafkaListenerContainerFactory")
        public void consumeAnalyzedChat(AnalyzedChatMessage message) {
                String roomId = message.getRoomId();
                String type = message.getMessageType() != null ? message.getMessageType() : "CHAT";

                switch (type) {
                        case "CHAT" -> handleChat(roomId, message);
                        case "DONATION" -> handleDonation(roomId, message);
                        case "SUBSCRIPTION" -> handleSubscription(roomId, message);
                        default -> log.warn("[Kafka] Unknown messageType={} for roomId={}", type, roomId);
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
                        streamRedisService.incrementEmotionStats(roomId, message.getEmotion().getType())
                                        .then(streamRedisService.getRoomStats(roomId))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                        stats -> {
                                                                Sinks.Many<Object> sink = roomSinks.get(roomId);
                                                                if (sink != null) {
                                                                        sink.tryEmitNext(Map.of("event",
                                                                                        "chat_analyzed", "data",
                                                                                        message));
                                                                        sink.tryEmitNext(Map.of("event", "stats_update",
                                                                                        "data", stats));
                                                                }
                                                        },
                                                        error -> log.error("Redis Update Error: {}",
                                                                        error.getMessage()));
                }
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
