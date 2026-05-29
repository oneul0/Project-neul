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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${gak.v2.spike.positive-threshold:0.55}")
    private double positiveThreshold;

    @Value("${gak.v2.spike.negative-threshold:0.45}")
    private double negativeThreshold;

    @Value("${gak.v2.similarity.threshold:0.72}")
    private double similarityThreshold;

    @Value("${gak.v2.similarity.alert-cooldown-minutes:3}")
    private int alertCooldownMinutes;

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
        log.info("[V2Stream][DEBUG] spike check roomId={} emaPos={} emaNeg={} trigger={}",
                frame.getRoomId(),
                frame.getMentalBuffer() != null ? frame.getMentalBuffer().getEmaPositive() : "null",
                frame.getMentalBuffer() != null ? frame.getMentalBuffer().getEmaNegative() : "null",
                trigger);
        if (trigger == null) return;

        Instant last = lastAlertAt.get(frame.getRoomId());
        if (last != null && Duration.between(last, Instant.now()).compareTo(Duration.ofMinutes(alertCooldownMinutes)) < 0) {
            log.info("[V2Stream][DEBUG] cooldown active, skipping. cooldownMinutes={}", alertCooldownMinutes);
            return;
        }

        lastAlertAt.put(frame.getRoomId(), Instant.now());
        String embeddingText = buildLiveEmbeddingText(frame);
        log.info("[V2Stream][DEBUG] requesting embedding. threshold={} sinkExists={}", similarityThreshold, roomSinks.containsKey(frame.getRoomId()));

        highlightEmbeddingService.requestEmbeddingPublic(embeddingText)
                .flatMap(vector -> {
                    log.info("[V2Stream][DEBUG] embedding received, querying pgvector...");
                    return highlightRetrievalService.findMostSimilarLive(
                            frame.getRoomId(), vector, similarityThreshold, trigger);
                })
                .doOnSuccess(alert -> {
                    if (alert == null) log.info("[V2Stream][DEBUG] pgvector returned no match above threshold={}", similarityThreshold);
                })
                .flatMap(alert -> {
                    String insightPrompt = buildInsightPrompt(frame, alert);
                    return highlightEmbeddingService.generateInsight(insightPrompt)
                            .map(insight -> V2SimilarHighlightAlert.builder()
                                    .roomId(alert.getRoomId())
                                    .highlightId(alert.getHighlightId())
                                    .videoNo(alert.getVideoNo())
                                    .sceneLabel(alert.getSceneLabel())
                                    .category(alert.getCategory())
                                    .reasonSummary(alert.getReasonSummary())
                                    .similarity(alert.getSimilarity())
                                    .trigger(alert.getTrigger())
                                    .detectedAt(alert.getDetectedAt())
                                    .insight(insight)
                                    .build())
                            .defaultIfEmpty(alert); // insight 생성 실패 시 원본 alert 반환
                })
                .subscribe(
                        alert -> {
                            Sinks.Many<Object> sink = roomSinks.get(frame.getRoomId());
                            log.info("[V2Stream][DEBUG] alert found scene={} similarity={} insight={} sinkExists={}",
                                    alert.getSceneLabel(), alert.getSimilarity(), alert.getInsight(), sink != null);
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
        if (mb.getEmaPositive() > positiveThreshold) return "positive_spike";
        if (mb.getEmaNegative() > negativeThreshold) return "negative_spike";
        return null;
    }

    private String buildLiveEmbeddingText(V2AggregateFrame frame) {
        MentalBufferState mb = frame.getMentalBuffer();
        double emaPos = mb != null ? mb.getEmaPositive() : 0;
        double emaNeg = mb != null ? mb.getEmaNegative() : 0;
        double balance = frame.getBalance();

        // dominant 감정 — VOD 임베딩의 emotionDominance 필드와 같은 공간으로 맞춤
        String dominant = emaPos >= 0.6 ? "positive"
                : emaNeg >= 0.5 ? "negative"
                : "neutral";

        String keywords = frame.getKeywords() != null
                ? String.join(" ", frame.getKeywords()) : "";

        List<AnchorChat> anchors = frame.getAnchors();
        String topAnchor = (anchors != null && !anchors.isEmpty())
                ? anchors.get(0).getContent() : "";

        // VOD buildEmbeddingText 와 동일한 필드 구조로 포맷 — 벡터 공간 정렬
        return String.format(
                "[%s] live\ndominant=%s density=%.1fx unique=%.2f\n" +
                "signal: hype=%.2f laugh=%.2f surprise=%.2f tension=%.2f\n" +
                "keywords: %s\n%s",
                safe(frame.getTopicLabel(), "unknown"),
                dominant,
                1.0 + emaPos,          // density 대응: 반응 밀도
                balance,               // unique 대응: 균형 지수
                emaPos,                // hype — 긍정 에너지
                Math.max(0, emaPos - emaNeg) * 0.5, // laugh — 여유로운 긍정
                Math.abs(emaPos - emaNeg),           // surprise — 감정 격차
                emaNeg,                              // tension — 부정 긴장
                keywords,
                topAnchor
        );
    }

    private String buildInsightPrompt(V2AggregateFrame frame, V2SimilarHighlightAlert alert) {
        List<AnchorChat> anchors = frame.getAnchors();
        String topAnchor = (anchors != null && !anchors.isEmpty()) ? anchors.get(0).getContent() : "";
        String keywords = frame.getKeywords() != null ? String.join(" ", frame.getKeywords()) : "";
        String reasonFirst = alert.getReasonSummary() != null
                ? alert.getReasonSummary().split("\\|")[0].strip()
                : "";

        return String.format(
                "다음 채팅 상황을 보고 지금 시청자들이 어떻게 반응하는지 구어체 한 문장으로만 출력해.\n\n" +
                "입력:\n" +
                "주제=%s 키워드=%s 채팅=\"%s\" 패턴=%s\n\n" +
                "출력 예시:\n" +
                "- 시청자들이 폭소하고 있어요\n" +
                "- 시청자들이 감동받고 있어요\n" +
                "- 시청자들이 비틱에 열받고 있어요\n" +
                "- 시청자들이 반전에 놀라고 있어요\n\n" +
                "규칙: 위 예시처럼 \"시청자들이 ~하고 있어요\" 형태, 15자 이내, 딱 그 한 줄만.\n\n" +
                "출력:",
                safe(frame.getTopicLabel(), ""),
                keywords,
                topAnchor,
                reasonFirst
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
