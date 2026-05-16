package com.gak.v2.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.AnchorChat;
import com.gak.v2.common.dto.MentalBufferState;
import com.gak.v2.common.dto.NarrativeBriefing;
import com.gak.v2.common.dto.V2AggregateFrame;
import com.gak.v2.common.dto.V2ContextResult;
import com.gak.v2.common.dto.V2RawChatMessage;
import com.gak.v2.common.dto.V2SentimentResult;
import com.gak.v2.common.dto.V2TrollResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2Aggregator {

    private final ObjectMapper objectMapper;
    private final V2EmaBufferService emaBufferService;
    private final V2AggregatePublisher aggregatePublisher;
    private final V2BriefingService briefingService;

    private final Map<String, RoomAggregateState> roomStates = new ConcurrentHashMap<>();
    private final Map<String, V2ContextResult> latestContexts = new ConcurrentHashMap<>();

    @KafkaListener(topics = "v2-raw-chat", groupId = "gak-v2-aggregate-raw-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeRaw(String json) {
        try {
            V2RawChatMessage message = objectMapper.readValue(json, V2RawChatMessage.class);
            if (!"CHAT".equals(message.getMessageType())) {
                return;
            }
        } catch (JsonProcessingException e) {
            log.error("[V2Aggregator] Failed to parse raw v2 message", e);
        }
    }

    @KafkaListener(topics = "v2-troll", groupId = "gak-v2-aggregate-troll-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeTroll(String json) {
        try {
            V2TrollResult result = objectMapper.readValue(json, V2TrollResult.class);
            roomStates.computeIfAbsent(result.getRoomId(), key -> new RoomAggregateState())
                    .updateTroll(result);
        } catch (JsonProcessingException e) {
            log.error("[V2Aggregator] Failed to parse troll result", e);
        }
    }

    @KafkaListener(topics = "v2-context", groupId = "gak-v2-aggregate-context-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeContext(String json) {
        try {
            V2ContextResult result = objectMapper.readValue(json, V2ContextResult.class);
            latestContexts.put(result.getRoomId(), result);
        } catch (JsonProcessingException e) {
            log.error("[V2Aggregator] Failed to parse context result", e);
        }
    }

    @KafkaListener(topics = "v2-sentiment", groupId = "gak-v2-aggregate-sentiment-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeSentiment(String json) {
        try {
            V2SentimentResult result = objectMapper.readValue(json, V2SentimentResult.class);
            RoomAggregateState state = roomStates.computeIfAbsent(result.getRoomId(), key -> new RoomAggregateState());
            state.updateSentiment(result);

            MentalBufferState bufferState = emaBufferService.update(
                    result.getRoomId(),
                    state.getAveragePositive(),
                    state.getAverageNegative());

            V2ContextResult context = latestContexts.get(result.getRoomId());
            java.util.List<AnchorChat> anchors = context != null && context.getAnchors() != null
                    ? context.getAnchors()
                    : java.util.List.of();
            String topAnchor = anchors.isEmpty() ? "" : anchors.get(0).getContent();
            NarrativeBriefing briefing = briefingService.create(
                    result.getRoomId(),
                    state.getBalance(),
                    bufferState.getEmaNegative(),
                    state.getFilteredCount(),
                    topAnchor);

            V2AggregateFrame frame = V2AggregateFrame.builder()
                    .roomId(result.getRoomId())
                    .emittedAt(LocalDateTime.now())
                    .balance(state.getBalance())
                    .mentalBuffer(bufferState)
                    .trustSummary(state.toTrustSummary())
                    .anchors(anchors)
                    .keywords(context != null ? context.getKeywords() : java.util.List.of())
                    .topicLabel(context != null ? context.getTopicLabel() : "LIVE_FLOW")
                    .briefing(briefing)
                    .stats(state.toStats())
                    .build();

            aggregatePublisher.publish(frame);
        } catch (JsonProcessingException e) {
            log.error("[V2Aggregator] Failed to parse sentiment result", e);
        }
    }

    private static class RoomAggregateState {
        private double positiveTotal;
        private double negativeTotal;
        private double neutralTotal;
        private long sentimentCount;
        private int filteredCount;
        private int trollCandidateCount;
        private int fanCount;

        void updateSentiment(V2SentimentResult result) {
            positiveTotal += result.getPositiveScore();
            negativeTotal += result.getNegativeScore();
            neutralTotal += result.getNeutralScore();
            sentimentCount++;
        }

        void updateTroll(V2TrollResult result) {
            if (result.isFiltered()) {
                filteredCount++;
            }
            if ("TROLL_CANDIDATE".equals(result.getTrustGrade())) {
                trollCandidateCount++;
            } else if ("FAN".equals(result.getTrustGrade())) {
                fanCount++;
            }
        }

        double getAveragePositive() {
            return sentimentCount == 0 ? 0.0 : positiveTotal / sentimentCount;
        }

        double getAverageNegative() {
            return sentimentCount == 0 ? 0.0 : negativeTotal / sentimentCount;
        }

        double getBalance() {
            double total = positiveTotal + negativeTotal + neutralTotal;
            return total == 0.0 ? 0.5 : positiveTotal / total;
        }

        int getFilteredCount() {
            return filteredCount;
        }

        Map<String, Object> toTrustSummary() {
            Map<String, Object> map = new HashMap<>();
            map.put("filteredCount", filteredCount);
            map.put("trollCandidateCount", trollCandidateCount);
            map.put("fanCount", fanCount);
            return map;
        }

        Map<String, Object> toStats() {
            Map<String, Object> map = new HashMap<>();
            map.put("positiveAverage", getAveragePositive());
            map.put("negativeAverage", getAverageNegative());
            map.put("neutralAverage", sentimentCount == 0 ? 0.0 : neutralTotal / sentimentCount);
            map.put("totalCount", sentimentCount);
            return map;
        }
    }
}
