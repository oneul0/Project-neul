package com.neul.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.analyzer.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.RawChatMessage;
import com.neul.analyzer.optimization.ChatOptimizer;
import com.neul.analyzer.optimization.OptimizedBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisProcessor {

    private final KafkaTemplate<String, AnalyzedChatMessage> kafkaTemplate;
    private final GeminiAnalyzerService geminiAnalyzerService;
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
    @KafkaListener(topics = "raw-chat-topic", groupId = "neul-analyzer-group", containerFactory = "batchKafkaListenerContainerFactory")
    public void processBatch(List<String> rawMessages) {
        if (rawMessages.isEmpty())
            return;

        log.debug("[Processor] Received batch of {} raw messages", rawMessages.size());

        // Step 1: JSON 파싱
        List<RawChatMessage> parsed = rawMessages.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, RawChatMessage.class);
                    } catch (JsonProcessingException e) {
                        log.error("[Processor] Failed to parse RawChatMessage: {}", json, e);
                        return null;
                    }
                })
                .filter(msg -> msg != null)
                .collect(Collectors.toList());

        if (parsed.isEmpty())
            return;

        // Step 2: 메시지 타입별 분기
        List<RawChatMessage> chatMessages = parsed.stream()
                .filter(m -> "CHAT".equals(m.getMessageType()))
                .collect(Collectors.toList());

        List<RawChatMessage> passthroughMessages = parsed.stream()
                .filter(m -> !"CHAT".equals(m.getMessageType()))
                .collect(Collectors.toList());

        // Step 3: DONATION / SUBSCRIPTION 패스스루 즉시 발행
        passthroughMessages.forEach(this::publishPassthrough);

        // Step 4: CHAT 메시지 → 최적화 → 감정 분석
        if (!chatMessages.isEmpty()) {
            analyzeAndPublish(chatMessages);
        }
    }

    // ─── DONATION / SUBSCRIPTION 패스스루 ────────────────────────────────────

    private void publishPassthrough(RawChatMessage msg) {
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

        kafkaTemplate.send(OUTPUT_TOPIC, passthrough.getRoomId(), passthrough);
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
        geminiAnalyzerService.analyzeBatch(optimized.getCompressedChats())
                .subscribe(
                        analyzed -> analyzed.forEach(msg -> {
                            kafkaTemplate.send(OUTPUT_TOPIC, msg.getRoomId(), msg);
                            log.info("[Processor] Sent analyzed CHAT: roomId={}, emotion={}",
                                    msg.getRoomId(), msg.getEmotion().getType());
                        }),
                        error -> log.error("[Processor] Analysis failed for batch", error));
    }
}
