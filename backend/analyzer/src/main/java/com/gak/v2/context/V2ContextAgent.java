package com.gak.v2.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.AnchorChat;
import com.gak.v2.common.dto.V2ContextResult;
import com.gak.v2.common.dto.V2RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2ContextAgent {

    private final ObjectMapper objectMapper;
    private final V2ContextPublisher contextPublisher;

    private final Map<String, Deque<V2RawChatMessage>> recentMessages = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "v2-raw-chat",
            groupId = "gak-v2-context-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(String json) {
        try {
            V2RawChatMessage message = objectMapper.readValue(json, V2RawChatMessage.class);
            if (!"CHAT".equals(message.getMessageType())) {
                return;
            }

            Deque<V2RawChatMessage> queue = recentMessages.computeIfAbsent(message.getRoomId(), key -> new ArrayDeque<>());
            synchronized (queue) {
                queue.addFirst(message);
                while (queue.size() > 40) {
                    queue.removeLast();
                }
            }

            List<V2RawChatMessage> snapshot;
            synchronized (queue) {
                snapshot = new ArrayList<>(queue);
            }

            List<AnchorChat> anchors = snapshot.stream()
                    .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                    .sorted(Comparator.comparingDouble(this::messageWeight).reversed())
                    .limit(3)
                    .map(item -> AnchorChat.builder()
                            .messageId(item.getMessageId())
                            .senderId(item.getSenderId())
                            .sender(item.getSender())
                            .content(item.getContent())
                            .weight(messageWeight(item))
                            .clusterId("context-live")
                            .build())
                    .toList();

            List<String> keywords = snapshot.stream()
                    .map(V2RawChatMessage::getContent)
                    .filter(content -> content != null && !content.isBlank())
                    .flatMap(content -> List.of(content.split("\\s+")).stream())
                    .map(String::trim)
                    .filter(token -> token.length() >= 2)
                    .limit(6)
                    .collect(
                            () -> new LinkedHashSet<String>(),
                            LinkedHashSet::add,
                            LinkedHashSet::addAll)
                    .stream()
                    .toList();

            String topicLabel = keywords.isEmpty() ? "LIVE_FLOW" : keywords.get(0);

            V2ContextResult result = V2ContextResult.builder()
                    .roomId(message.getRoomId())
                    .windowStart(LocalDateTime.now().minusSeconds(20))
                    .windowEnd(LocalDateTime.now())
                    .anchors(anchors)
                    .keywords(keywords)
                    .topicLabel(topicLabel)
                    .build();

            contextPublisher.publish(result);
        } catch (JsonProcessingException e) {
            log.error("[V2ContextAgent] Failed to parse v2 raw chat payload", e);
        }
    }

    private double messageWeight(V2RawChatMessage message) {
        String content = message.getContent() == null ? "" : message.getContent().trim();
        double lengthWeight = Math.min(content.length() / 40.0, 1.0);
        double punctuationWeight = content.contains("?") || content.contains("!") ? 0.15 : 0.0;
        return lengthWeight + punctuationWeight;
    }
}
