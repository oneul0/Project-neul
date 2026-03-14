package com.neul.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import com.neul.analyzer.optimization.ChatOptimizer;
import com.neul.analyzer.optimization.OptimizedBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisProcessor {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OllamaAnalyzerService analyzerService;
    private final ObjectMapper objectMapper;
    private final ChatOptimizer chatOptimizer;

    private static final String OUTPUT_TOPIC = "analyzed-chat-topic";

    /**
     * raw-chat-topic에서 배치로 메시지를 수신합니다.
     * <p>
     * - CHAT 타입 → ChatOptimizer 최적화 → Gemini 감정 분석 → analyzed-chat-topic
     * - DONATION 타입 → 분석 없이 패스스루 → analyzed-chat-topic
     * - SUBSCRIPTION 타입 → 분석 없이 패스스루 → analyzed-chat-topic
     */
    @KafkaListener(topics = "raw-chat-batch-topic", groupId = "neul-analyzer-group", containerFactory = "batchKafkaListenerContainerFactory")
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
                .collect(Collectors.toList());

        List<RawChatMessage> passthroughMessages = parsed.stream()
                .filter(m -> !"CHAT".equals(m.getMessageType()))
                .collect(Collectors.toList());

        // Step 3: DONATION / SUBSCRIPTION 패스스루 즉시 발행
        passthroughMessages.forEach(this::publishPassthrough);

        // Step 4: CHAT 메시지 분리 (일반 채팅 vs 투표 명령어)
        List<RawChatMessage> regularChats = new ArrayList<>();
        List<RawChatMessage> voteCommands = new ArrayList<>();

        for (RawChatMessage msg : chatMessages) {
            String content = msg.getContent() != null ? msg.getContent().trim() : "";
            if (content.startsWith("!") && content.length() > 1 && content.substring(1).split(" ")[0].matches("\\d+")) {
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
        // 최적화 (필터링 + 압축)
        OptimizedBatch optimized = chatOptimizer.optimize(chatMessages);
        if (optimized.getCompressedChats().isEmpty()) {
            log.info("[Processor] All chat messages filtered out. Skipping analysis.");
            return;
        }

        // Gemini 감정 분석
        analyzerService.analyzeBatch(optimized.getCompressedChats())
                .subscribe(
                        analyzed -> analyzed.forEach(msg -> {
                            try {
                                kafkaTemplate.send(OUTPUT_TOPIC, msg.getRoomId(), objectMapper.writeValueAsString(msg));
                                log.info("[Processor] Sent analyzed CHAT: roomId={}, scores={}",
                                        msg.getRoomId(), msg.getEmotionScores());
                            } catch (JsonProcessingException e) {
                                log.error("[Processor] Failed to serialize analyzed CHAT", e);
                            }
                        }),
                        error -> log.error("[Processor] Analysis failed for batch", error));
    }
}
