package com.gak.v2.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.core_api.rag.HighlightEmbeddingService;
import com.gak.core_api.rag.HighlightRetrievalService;
import com.gak.v2.common.dto.AnchorChat;
import com.gak.v2.common.dto.MentalBufferState;
import com.gak.v2.common.dto.V2AggregateFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2StreamService {

    private static final double POSITIVE_SPIKE_THRESHOLD = 0.55;
    private static final double NEGATIVE_SPIKE_THRESHOLD = 0.45;
    private static final double SIMILARITY_THRESHOLD     = 0.72;
    private static final Duration ALERT_COOLDOWN         = Duration.ofMinutes(3);

    private final ObjectMapper objectMapper;
    private final V2RedisStateService v2RedisStateService;
    private final HighlightEmbeddingService highlightEmbeddingService;
    private final HighlightRetrievalService highlightRetrievalService;

    private final Map<String, Sinks.Many<Object>> roomSinks    = new ConcurrentHashMap<>();
    private final Map<String, V2AggregateFrame>   latestFrames = new ConcurrentHashMap<>();
    private final Map<String, Instant>            lastAlertAt  = new ConcurrentHashMap<>();

    @KafkaListener(topics = "v2-aggregate", groupId = "gak-v2-core-api-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeAggregate(String json) {
        try {
            V2AggregateFrame frame = objectMapper.readValue(json, V2AggregateFrame.class);
            latestFrames.put(frame.getRoomId(), frame);
            v2RedisStateService.saveLatestFrame(frame).subscribe();

            Sinks.Many<Object> sink = roomSinks.get(frame.getRoomId());
            if (sink != null) {
                sink.tryEmitNext(Map.of("event", "v2_frame", "data", frame));
            }

            handleSpikeDetection(frame);
        } catch (JsonProcessingException e) {
            log.error("[V2StreamService] Failed to parse v2 aggregate payload", e);
        }
    }

    private void handleSpikeDetection(V2AggregateFrame frame) {
        String trigger = detectSpikeTrigger(frame);
        if (trigger == null) return;

        Instant last = lastAlertAt.get(frame.getRoomId());
        if (last != null && Duration.between(last, Instant.now()).compareTo(ALERT_COOLDOWN) < 0) return;

        lastAlertAt.put(frame.getRoomId(), Instant.now());
        String embeddingText = buildLiveEmbeddingText(frame);

        highlightEmbeddingService.requestEmbeddingPublic(embeddingText)
                .flatMap(vector -> highlightRetrievalService.findMostSimilarLive(
                        frame.getRoomId(), vector, SIMILARITY_THRESHOLD, trigger))
                .subscribe(
                        alert -> {
                            Sinks.Many<Object> sink = roomSinks.get(frame.getRoomId());
                            if (sink != null) {
                                sink.tryEmitNext(Map.of("event", "v2_similar_highlight", "data", alert));
                                log.info("[V2Stream] Similar highlight alert emitted. roomId={} scene={} similarity={}",
                                        frame.getRoomId(), alert.getSceneLabel(), alert.getSimilarity());
                            }
                        },
                        error -> log.warn("[V2Stream] Similar highlight search failed: {}", error.getMessage())
                );
    }

    private String detectSpikeTrigger(V2AggregateFrame frame) {
        MentalBufferState mb = frame.getMentalBuffer();
        if (mb == null) return null;
        if (mb.getEmaPositive() > POSITIVE_SPIKE_THRESHOLD) return "positive_spike";
        if (mb.getEmaNegative() > NEGATIVE_SPIKE_THRESHOLD) return "negative_spike";
        return null;
    }

    private String buildLiveEmbeddingText(V2AggregateFrame frame) {
        MentalBufferState mb = frame.getMentalBuffer();
        double emaPos = mb != null ? mb.getEmaPositive() : 0;
        double emaNeg = mb != null ? mb.getEmaNegative() : 0;

        String keywords = frame.getKeywords() != null
                ? String.join(" ", frame.getKeywords()) : "";

        List<AnchorChat> anchors = frame.getAnchors();
        String topAnchor = (anchors != null && !anchors.isEmpty())
                ? anchors.get(0).getContent() : "";

        return String.format(
                "[LIVE] %s\nbalance=%.2f positive=%.2f negative=%.2f\nkeywords: %s\n%s",
                safe(frame.getTopicLabel(), "unknown"),
                frame.getBalance(), emaPos, emaNeg,
                keywords, topAnchor
        );
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    public Flux<Object> subscribeRoom(String roomId) {
        Sinks.Many<Object> sink = roomSinks.computeIfAbsent(roomId, key -> Sinks.many().replay().limit(20));
        V2AggregateFrame latest = latestFrames.get(roomId);
        Mono<Object> initialFrame = latest != null
                ? Mono.just(Map.of("event", "v2_frame", "data", latest))
                : v2RedisStateService.getLatestFrame(roomId)
                        .map(frame -> {
                            latestFrames.put(roomId, frame);
                            return Map.of("event", "v2_frame", "data", frame);
                        });

        return initialFrame
                .flux()
                .concatWith(sink.asFlux())
                .doFinally(signalType -> {
                    if (sink.currentSubscriberCount() == 0) {
                        roomSinks.remove(roomId);
                    }
                });
    }
}
