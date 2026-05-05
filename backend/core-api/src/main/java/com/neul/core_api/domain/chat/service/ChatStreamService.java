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
import org.springframework.beans.factory.annotation.Value;
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

        private static final String DEFAULT_HIGHLIGHT_IMAGE_URL = "https://placehold.co/1280x720?text=highlight";

        private final AnalyzedChatRepository analyzedChatRepository;
        private final HighlightRepository highlightRepository;
        private final StreamRedisService streamRedisService;
        private final ObjectMapper objectMapper;
        private final WebClient.Builder webClientBuilder;

        @Value("${app.chzzk.live-status-base-url:https://api.chzzk.naver.com/service/v2/channels}")
        private String liveStatusBaseUrl;

        @Value("${app.collector.base-url:http://localhost:8081}")
        private String collectorBaseUrl;

        // Room(channelId)별 SSE Sink 맵 (replay 최근 100개 버퍼)
        private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();

        // 하이라이트 감지를 위한 최근 고득점 감정 기록 (roomId -> LastPeak)
        private final Map<String, HighlightRecord> lastHighlights = new ConcurrentHashMap<>();

        // 감정 강도 이동 평균 (roomId -> RollingAvg)
        private final Map<String, Double> intensityRollingAvg = new ConcurrentHashMap<>();

        /**
         * analyzed-chat-topic 소비.
         * CHAT -> DB 저장 + Redis 통계 갱신 + SSE 푸시
         * DONATION / SUBSCRIPTION -> SSE 패스스루만 (DB·Redis 저장 없음)
         */
        @KafkaListener(topics = "analyzed-chat-topic", groupId = "neul-core-api-group", containerFactory = "kafkaListenerContainerFactory")
        public void consumeAnalyzedChat(String json) {
                try {
                        AnalyzedChatMessage message = objectMapper.readValue(json, AnalyzedChatMessage.class);
                        String roomId = message.getRoomId();
                        String type = message.getMessageType() != null ? message.getMessageType() : "CHAT";

                        switch (type) {
                                case "CHAT" -> handleChat(roomId, message);
                                case "VOTE" -> handleVote(roomId, message);
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
                log.info("[Kafka] CHAT: roomId={}, sender={}, senderId={}, content={}",
                                roomId, message.getSender(), message.getSenderId(),
                                message.getContent());

                Map.Entry<String, Double> topEmotion = getTopEmotion(message.getEmotionScores());

                // 2. Session-based PostgreSQL Persistence
                streamRedisService.isCollectionActive(roomId)
                                .flatMap(active -> {
                                        if (Boolean.TRUE.equals(active)) {
                                                AnalyzedChat entity = AnalyzedChat.builder()
                                                                .messageId(message.getMessageId())
                                                                .roomId(roomId)
                                                                .content(message.getContent())
                                                                .sender(message.getSender())
                                                                .senderId(message.getSenderId()) // Added for Phase 23
                                                                .emotionType(topEmotion.getKey())
                                                                .emotionScore(topEmotion.getValue())
                                                                .analyzedAt(message.getAnalyzedAt())
                                                                .build();

                                                return analyzedChatRepository.save(entity)
                                                                .doOnNext(saved -> log.debug("Saved session CHAT to DB: id={}", saved.getId()))
                                                                .doOnError(err -> log.error("DB Save Error: {}", err.getMessage()));
                                        }
                                        return Mono.empty();
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                    success -> log.debug("DB Process finished"),
                                    err -> log.error("General DB Process Error: {}", err.getMessage())
                                );

                // 3. Redis 통계 갱신 + SSE 이벤트 발행
                if (message.getEmotionScores() != null && !message.getEmotionScores().isEmpty()) {
                        streamRedisService.updateMultiEmotionStats(roomId, message.getEmotionScores())
                                        .then(streamRedisService.getRoomStats(roomId))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                        stats -> {
                                                                Sinks.Many<Object> sink = roomSinks.get(roomId);
                                                                if (sink != null) {
                                                                        sink.tryEmitNext(Map.of("event", "chat_analyzed", "data", message));
                                                                        sink.tryEmitNext(Map.of("event", "stats_update", "data", stats));

                                                                        // 키워드 그룹 처리 (투표/키워드 풀용)
                                                                        if (message.getKeywordGroups() != null && !message.getKeywordGroups().isEmpty()) {
                                                                                streamRedisService.recordKeywordGroupsAndGetStats(roomId, message.getKeywordGroups())
                                                                                                .subscribe(keywordStats -> sink.tryEmitNext(Map.of("event", "keyword_update", "data", keywordStats)));
                                                                        }

                                                                        // 하이라이트 감지 로직 (Relative Spike Detection)
                                                                        double currentIntensity = calculateIntensity(message.getEmotionScores());
                                                                        updateRollingAvg(roomId, currentIntensity);

                                                                        double avg = intensityRollingAvg.getOrDefault(roomId, 0.5);

                                                                        // 조건: 1) 절대 임계값 0.6 이상 2) 최근 평균 대비 1.5배 이상 스파이크
                                                                        if (currentIntensity >= 0.6 && currentIntensity > avg * 1.5) {
                                                                                detectAndEmitHighlight(roomId, message, topEmotion, currentIntensity);
                                                                        }
                                                                }
                                                        },
                                                        error -> log.error("Redis Update Error: {}",
                                                                        error.getMessage()));
                }
        }

        private Map.Entry<String, Double> getTopEmotion(Map<String, Double> scores) {
                if (scores == null || scores.isEmpty()) {
                        return Map.entry("NEUTRAL", 1.0);
                }
                return scores.entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .orElse(Map.entry("NEUTRAL", 1.0));
        }

        private double calculateIntensity(Map<String, Double> scores) {
                if (scores == null)
                        return 0.0;
                // NEUTRAL을 제외한 모든 감정의 합산 강도
                return scores.entrySet().stream()
                                .filter(e -> !"NEUTRAL".equals(e.getKey()))
                                .mapToDouble(Map.Entry::getValue)
                                .sum();
        }

        private void updateRollingAvg(String roomId, double intensity) {
                // 단순 지수 이동 평균 (α = 0.2)
                intensityRollingAvg.compute(roomId, (k, v) -> (v == null) ? intensity : (v * 0.8 + intensity * 0.2));
        }

        private void detectAndEmitHighlight(String roomId, AnalyzedChatMessage message, Map.Entry<String, Double> topEmotion, double intensity) {
                // 최근 10초 내에 하이라이트가 있었다면 스킵 (중복 방지)
                HighlightRecord last = lastHighlights.get(roomId);
                if (last != null && last.getTimestamp().isAfter(LocalDateTime.now().minusSeconds(10))) {
                        return;
                }

                log.info("[Highlight] Relative spike detected! roomId={}, emotion={}, intensity={}",
                                roomId, topEmotion.getKey(), String.format("%.2f", intensity));

                fetchLiveImageUrl(roomId)
                                .map(this::normalizeHighlightImageUrl)
                                .filter(url -> !url.isBlank())
                                .defaultIfEmpty(DEFAULT_HIGHLIGHT_IMAGE_URL)
                                .onErrorReturn(DEFAULT_HIGHLIGHT_IMAGE_URL)
                                .flatMap(imageUrl -> {
                                        HighlightRecord highlight = HighlightRecord.builder()
                                                        .roomId(roomId)
                                                        .emotionType(topEmotion.getKey())
                                                        .peakScore(intensity) // 전체 강도를 peakScore로 저장
                                                        .topMessage(message.getContent())
                                                        .liveImageUrl(imageUrl)
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

        // ─── VOTE ───────────────────────────────────────────────────────────────

        private void handleVote(String roomId, AnalyzedChatMessage message) {
                log.info("[Kafka] VOTE: roomId={}, sender={}, senderId={}, content={}",
                                roomId, message.getSender(), message.getSenderId(),
                                message.getContent());

                // 1. Record Vote in Redis (option number) + display name mapping
                String content = message.getContent() != null ? message.getContent().trim() : "";
                // "!투표 N" 형식: "!투표 " 이후 첫 번째 토큰이 옵션 번호
                String voteOption = content.startsWith("!투표 ")
                        ? content.substring("!투표 ".length()).trim().split("\\s+")[0]
                        : content.substring(1).split(" ")[0]; // fallback (구형 !N 형식)
                String senderId = message.getSenderId() != null ? message.getSenderId() : message.getSender();
                String displayName = message.getSender() != null ? message.getSender() : senderId;

                streamRedisService.recordVote(roomId, senderId, voteOption)
                        .subscribe(success -> log.debug("Vote recorded: user={}, option={}", senderId, voteOption));

                if (displayName != null && !displayName.equals(senderId)) {
                        streamRedisService.recordVoterName(roomId, senderId, displayName)
                                .subscribe();
                }

                // 2. Save to DB for history (if session active)
                streamRedisService.isCollectionActive(roomId)
                                .flatMap(active -> {
                                        if (Boolean.TRUE.equals(active)) {
                                                AnalyzedChat entity = AnalyzedChat.builder()
                                                                .messageId(message.getMessageId())
                                                                .roomId(roomId)
                                                                .content(message.getContent())
                                                                .sender(message.getSender())
                                                                .senderId(message.getSenderId())
                                                                .emotionType("VOTE") // Specific tag for votes
                                                                .emotionScore(1.0)
                                                                .analyzedAt(message.getAnalyzedAt())
                                                                .build();

                                                return analyzedChatRepository.save(entity);
                                        }
                                        return Mono.empty();
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();

                // 3. Emit SSE (No stats update, but maybe a simple notification if needed - current dashboard doesn't use it for Min-sim)
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
                                                log.info("Removed unused sink for room: {}. Notifying collector to stop.", roomId);
                                                stopCollector(roomId);
                                        }
                                });
        }

        private Mono<String> fetchLiveImageUrl(String roomId) {
                return webClientBuilder.build()
                                .get()
                                .uri(liveStatusBaseUrl + "/" + roomId + "/live-status")
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .map(node -> node.path("content").path("liveImageUrl").asText());
        }

        private String normalizeHighlightImageUrl(String imageUrl) {
                if (imageUrl == null || imageUrl.isBlank()) {
                        return DEFAULT_HIGHLIGHT_IMAGE_URL;
                }
                return imageUrl.replace("{type}", "1080");
        }

        private void stopCollector(String roomId) {
                // collector 모듈의 DELETE /api/v1/channels/{roomId}/subscribe 호출
                webClientBuilder.build()
                                .delete()
                                .uri(collectorBaseUrl + "/api/v1/channels/" + roomId + "/subscribe")
                                .retrieve()
                                .toBodilessEntity()
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                                res -> log.info("[Lifecycle] Successfully unsubscribed roomId={} from collector", roomId),
                                                err -> log.error("[Lifecycle] Failed to unsubscribe roomId={} from collector: {}", roomId, err.getMessage()));
        }
}
