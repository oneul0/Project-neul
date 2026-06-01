package com.gak.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.AnalyzedChatMessage;
import com.gak.common.dto.RawChatBatch;
import com.gak.common.dto.RawChatMessage;
import com.gak.analyzer.optimization.ChatOptimizer;
import com.gak.analyzer.optimization.OptimizedBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisProcessor {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OllamaAnalyzerService analyzerService;
    private final HeuristicSentimentAnalyzer heuristicAnalyzer;
    private final ObjectMapper objectMapper;
    private final ChatOptimizer chatOptimizer;
    private final MeterRegistry meterRegistry;

    private static final String OUTPUT_TOPIC = "analyzed-chat-topic";

    /**
     * raw-chat-topic에서 배치로 메시지를 수신합니다.
     * <p>
     * - CHAT 타입 → ChatOptimizer 최적화 → LLM 감정 분석 → analyzed-chat-topic
     * - DONATION 타입 → 분석 없이 패스스루 → analyzed-chat-topic
     * - SUBSCRIPTION 타입 → 분석 없이 패스스루 → analyzed-chat-topic
     */
    @KafkaListener(topics = "raw-chat-batch-topic", groupId = "gak-analyzer-group", containerFactory = "batchKafkaListenerContainerFactory")
    public void processBatch(List<String> rawBatchJsons) {
        if (rawBatchJsons.isEmpty())
            return;

        log.debug("[Processor] Received {} batch messages", rawBatchJsons.size());

        for (String json : rawBatchJsons) {
            try {
                RawChatBatch batch = objectMapper.readValue(json, RawChatBatch.class);
                processSingleBatch(batch);
            } catch (JsonProcessingException e) {
                log.error("[Processor] Failed to parse RawChatBatch: {}", json, e);
            }
        }
    }

    private void processSingleBatch(RawChatBatch batch) {
        List<RawChatMessage> parsed = batch.getMessages();
        if (parsed == null || parsed.isEmpty())
            return;

        log.debug("[Processor] Processing batch from channel {} with {} messages", batch.getRoomId(), parsed.size());

        // Step 2: 메시지 타입별 분기
        List<RawChatMessage> chatMessages = parsed.stream()
                .filter(m -> "CHAT".equals(m.getMessageType()))
                .toList();

        List<RawChatMessage> passthroughMessages = parsed.stream()
                .filter(m -> !"CHAT".equals(m.getMessageType()))
                .toList();

        // Step 3: DONATION / SUBSCRIPTION 패스스루 즉시 발행
        passthroughMessages.forEach(this::publishPassthrough);

        // Step 4: CHAT 메시지 분리 (일반 채팅 vs 투표 명령어)
        List<RawChatMessage> regularChats = new ArrayList<>();
        List<RawChatMessage> voteCommands = new ArrayList<>();

        for (RawChatMessage msg : chatMessages) {
            String content = msg.getContent() != null ? msg.getContent().trim() : "";
            if (content.startsWith("!투표 ") && content.length() > "!투표 ".length()
                    && content.substring("!투표 ".length()).trim().split("\\s+")[0].matches("\\d+")) {
                voteCommands.add(msg);
            } else {
                regularChats.add(msg);
            }
        }

        // Step 5: 투표 명령어 패스스루 발행
        voteCommands.forEach(this::publishVotePassthrough);

        // Step 6: 일반 CHAT 메시지 → 최적화 → 감정 분석
        if (!regularChats.isEmpty()) {
            analyzeAndPublish(regularChats);
        }
    }

    private void publishVotePassthrough(RawChatMessage msg) {
        try {
            AnalyzedChatMessage voteMsg = AnalyzedChatMessage.builder()
                    .messageId(msg.getMessageId())
                    .roomId(msg.getRoomId())
                    .messageType("VOTE")
                    .content(msg.getContent())
                    .sender(msg.getSender())
                    .senderId(msg.getSenderId())
                    .analyzedAt(LocalDateTime.now())
                    .build();
            
            kafkaTemplate.send(OUTPUT_TOPIC, voteMsg.getRoomId(), objectMapper.writeValueAsString(voteMsg));
            log.info("[Processor] Passthrough VOTE command: {} from {} in channel {}", 
                    msg.getContent(), msg.getSender(), msg.getRoomId());
        } catch (JsonProcessingException e) {
            log.error("[Processor] Failed to serialize VOTE message", e);
        }
    }

    // ─── DONATION / SUBSCRIPTION 패스스루 ────────────────────────────────────

    private void publishPassthrough(RawChatMessage msg) {
        try {
            AnalyzedChatMessage passthrough;

            if ("DONATION".equals(msg.getMessageType())) {
                passthrough = AnalyzedChatMessage.builder()
                        .messageId(msg.getMessageId())
                        .roomId(msg.getRoomId())
                        .messageType("DONATION")
                        .donationType(msg.getDonationType())
                        .donatorNickname(msg.getDonatorNickname())
                        .payAmount(msg.getPayAmount())
                        .donationText(msg.getDonationText())
                        .analyzedAt(LocalDateTime.now())
                        .build();
                log.info("[Processor] Passthrough DONATION: {}원 from {} in channel {}",
                        msg.getPayAmount(), msg.getDonatorNickname(), msg.getRoomId());
            } else { // SUBSCRIPTION
                passthrough = AnalyzedChatMessage.builder()
                        .messageId(msg.getMessageId())
                        .roomId(msg.getRoomId())
                        .messageType("SUBSCRIPTION")
                        .subscriberNickname(msg.getSubscriberNickname())
                        .tierNo(msg.getTierNo())
                        .tierName(msg.getTierName())
                        .month(msg.getMonth())
                        .analyzedAt(LocalDateTime.now())
                        .build();
                log.info("[Processor] Passthrough SUBSCRIPTION: tier{} {}개월 from {} in channel {}",
                        msg.getTierNo(), msg.getMonth(), msg.getSubscriberNickname(), msg.getRoomId());
            }

            kafkaTemplate.send(OUTPUT_TOPIC, passthrough.getRoomId(), objectMapper.writeValueAsString(passthrough));
        } catch (JsonProcessingException e) {
            log.error("[Processor] Failed to serialize analyzed message", e);
        }
    }

    // ─── CHAT 감정 분석 ───────────────────────────────────────────────────────

    private void analyzeAndPublish(List<RawChatMessage> chatMessages) {
        // Step 1: Fast-Path (Heuristic)
        List<RawChatMessage> ambiguousMessages = new ArrayList<>();
        Map<String, AnalyzedChatMessage> fallbackByMessageId = new LinkedHashMap<>();

        chatMessages.forEach(msg -> {
            AnalyzedChatMessage fastResult = heuristicAnalyzer.analyze(msg);
            
            // 모호한 채팅만 Slow-Path 후보로 선정
            if (fastResult.isAmbiguous()) {
                ambiguousMessages.add(msg);
                fallbackByMessageId.put(msg.getMessageId(), fastResult);
                return;
            }

            // 확정 가능한 채팅만 즉시 발행하고, 모호한 채팅은 LLM 또는 fallback 중 한 번만 발행한다.
            publishToTopic(fastResult, "[Processor] Sent Fast-Path CHAT");
            meterRegistry.counter("gak.chat.analyzed", "path", "fast").increment();
        });

        // Step 2: Slow-Path (LLM Deep Analysis) - Only for Ambiguous chats
        if (ambiguousMessages.isEmpty()) {
            log.debug("[Processor] No ambiguous chats in batch. Skipping LLM analysis.");
            return;
        }

        // 최적화 (필터링 + 압축)
        OptimizedBatch optimized = chatOptimizer.optimize(ambiguousMessages);
        if (optimized.getCompressedChats().isEmpty()) {
            log.info("[Processor] Ambiguous chats filtered out. Publishing heuristic fallbacks.");
            publishFallbackMessages(fallbackByMessageId, Set.of());
            return;
        }

        log.info("[Processor] Sending {} ambiguous chats (compressed to {}) to LLM", 
                ambiguousMessages.size(), optimized.getCompressedChats().size());

        // LLM 감정 분석
        analyzerService.analyzeBatch(optimized.getCompressedChats())
                .subscribe(
                        analyzed -> {
                            Set<String> llmMessageIds = analyzed.stream()
                                    .map(AnalyzedChatMessage::getMessageId)
                                    .collect(Collectors.toSet());
                            analyzed.forEach(msg -> {
                                publishToTopic(msg, "[Processor] Sent Slow-Path (LLM) CHAT");
                                meterRegistry.counter("gak.chat.analyzed", "path", "slow").increment();
                            });
                            publishFallbackMessages(fallbackByMessageId, llmMessageIds);
                        },
                        error -> {
                            log.error("[Processor] LLM Analysis failed for batch", error);
                            publishFallbackMessages(fallbackByMessageId, Set.of());
                        });
    }

    private void publishFallbackMessages(Map<String, AnalyzedChatMessage> fallbackByMessageId, Set<String> alreadyPublishedIds) {
        fallbackByMessageId.forEach((messageId, fallback) -> {
            if (alreadyPublishedIds.contains(messageId)) {
                return;
            }
            publishToTopic(fallback, "[Processor] Sent Fast-Path fallback CHAT");
            meterRegistry.counter("gak.chat.analyzed", "path", "fast_fallback").increment();
        });
    }

    private void publishToTopic(AnalyzedChatMessage msg, String logPrefix) {
        try {
            kafkaTemplate.send(OUTPUT_TOPIC, msg.getRoomId(), objectMapper.writeValueAsString(msg));
            log.info("{} : roomId={}, scores={}", logPrefix, msg.getRoomId(), msg.getEmotionScores());
        } catch (JsonProcessingException e) {
            log.error("[Processor] Failed to serialize analyzed message", e);
        }
    }
}
