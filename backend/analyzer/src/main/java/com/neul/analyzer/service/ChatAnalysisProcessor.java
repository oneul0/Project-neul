package com.neul.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.analyzer.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.RawChatMessage;
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

    private static final String OUTPUT_TOPIC = "analyzed-chat-topic";

    /**
     * raw-chat-topic에서 배치로 메시지를 수신하여 감정 분석 후 analyzed-chat-topic으로 전송
     * MAX_POLL_RECORDS=50 설정으로 최대 50건씩 배치 처리
     */
    @KafkaListener(
            topics = "raw-chat-topic",
            groupId = "neul-analyzer-group",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void processBatch(List<String> rawMessages) {
        if (rawMessages.isEmpty()) return;

        log.debug("Received batch of {} raw messages", rawMessages.size());

        // JSON 파싱
        List<RawChatMessage> chats = rawMessages.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, RawChatMessage.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse RawChatMessage: {}", json, e);
                        return null;
                    }
                })
                .filter(msg -> msg != null)
                .collect(Collectors.toList());

        if (chats.isEmpty()) return;

        // 감정 분석 (동기 블로킹 — @KafkaListener는 일반 스레드에서 실행)
        geminiAnalyzerService.analyzeBatch(chats)
                .subscribe(
                        analyzed -> analyzed.forEach(msg -> {
                            kafkaTemplate.send(OUTPUT_TOPIC, msg.getRoomId(), msg);
                            log.info("[Analyzer] Sent analyzed chat: roomId={}, emotion={}",
                                    msg.getRoomId(), msg.getEmotion().getType());
                        }),
                        error -> log.error("Analysis failed for batch", error)
                );
    }
}
