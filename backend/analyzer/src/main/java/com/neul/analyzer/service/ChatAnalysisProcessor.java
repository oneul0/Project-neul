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
     * raw-chat-topic에서 배치로 메시지를 수신하여 최적화 후 감정 분석을 수행하고
     * analyzed-chat-topic으로 전송합니다.
     *
     * <p>
     * 처리 순서:
     * <ol>
     * <li>JSON 파싱 → {@code List<RawChatMessage>}</li>
     * <li>{@link ChatOptimizer#optimize}: 스팸 필터링(A) + 중복 압축(B)</li>
     * <li>{@link GeminiAnalyzerService#analyzeBatch}: 압축된 배치 감정 분석</li>
     * <li>분석 결과 → analyzed-chat-topic 발행</li>
     * </ol>
     */
    @KafkaListener(topics = "raw-chat-topic", groupId = "neul-analyzer-group", containerFactory = "batchKafkaListenerContainerFactory")
    public void processBatch(List<String> rawMessages) {
        if (rawMessages.isEmpty())
            return;

        log.debug("[Processor] Received batch of {} raw messages", rawMessages.size());

        // Step 1: JSON 파싱
        List<RawChatMessage> chats = rawMessages.stream()
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

        if (chats.isEmpty())
            return;

        // Step 2: 최적화 (필터링 + 압축)
        OptimizedBatch optimized = chatOptimizer.optimize(chats);
        if (optimized.getCompressedChats().isEmpty()) {
            log.info("[Processor] All messages filtered out. Skipping analysis.");
            return;
        }

        // Step 3: 감정 분석 (동기 블로킹 — @KafkaListener는 일반 스레드에서 실행)
        geminiAnalyzerService.analyzeBatch(optimized.getCompressedChats())
                .subscribe(
                        analyzed -> analyzed.forEach(msg -> {
                            kafkaTemplate.send(OUTPUT_TOPIC, msg.getRoomId(), msg);
                            log.info("[Processor] Sent analyzed chat: roomId={}, emotion={}",
                                    msg.getRoomId(), msg.getEmotion().getType());
                        }),
                        error -> log.error("[Processor] Analysis failed for batch", error));
    }
}
