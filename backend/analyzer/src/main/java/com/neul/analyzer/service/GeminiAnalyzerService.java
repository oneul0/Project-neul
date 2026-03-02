package com.neul.analyzer.service;

import com.neul.analyzer.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.Emotion;
import com.neul.analyzer.dto.RawChatMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiAnalyzerService {

    private final Random random = new Random();

    /**
     * Micro-batching을 통해 전달받은 채팅 메시지 리스트를 분석합니다.
     * 실제 서비스에서는 Vertex AI Gemini API를 호출하여 배치를 분석합니다.
     * resilience4j의 @CircuitBreaker를 통해 외부 API 호출 장애를 격리시킵니다.
     *
     * @param chats 분석할 메시지 원본 리스트
     * @return 각 메시지의 분석된 AnalyzedChatMessage 매핑 결과 리스트
     */
    @CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
    public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<RawChatMessage> chats) {
        log.info("Requesting Gemini API to analyze batch of {} messages", chats.size());
        
        // TODO: 실제 Vertex AI 통신 WebClient 로직 구현 및 파싱
        // 현재는 임시로 무작위 감정 분석 결과를 생성합니다.
        
        return Mono.fromCallable(() -> {
            // 임의의 딜레이(API 호출 흉내)
            Thread.sleep(100); 
            
            return chats.stream()
                    .map(this::simulateEmotion)
                    .collect(Collectors.toList());
        });
    }

    /**
     * Fallback 메서드. Vertex AI API 호출 실패 시 (타임아웃, 서킷브레이커 오픈 등)
     * 기본 감정 상태로 처리합니다. (DLQ로 보내거나 NEUTRAL 처리)
     */
    public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(List<RawChatMessage> chats, Throwable t) {
        log.error("Gemini API call failed. CircuitBreaker fallback triggered. Cause: {}", t.getMessage());
        List<AnalyzedChatMessage> fallbackMessages = chats.stream()
                .map(chat -> AnalyzedChatMessage.builder()
                        .messageId(chat.getMessageId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .emotion(Emotion.builder().type("NEUTRAL").score(0.0).build())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        return Mono.just(fallbackMessages);
    }

    private AnalyzedChatMessage simulateEmotion(RawChatMessage chat) {
        String text = chat.getContent();
        int r = random.nextInt(10);
        String type;
        double score;
        
        if (text.contains("재밌") || text.contains("ㅋㅋ") || text.contains("화이팅") || text.contains("대박") || r >= 7) {
            type = "POSITIVE";
            score = 0.5 + (random.nextDouble() * 0.5); // 0.5 ~ 1.0
        } else if (text.contains("아쉽") || text.contains("별로") || r <= 1) {
            type = "NEGATIVE";
            score = -0.5 - (random.nextDouble() * 0.5); // -0.5 ~ -1.0
        } else {
            type = "NEUTRAL";
            score = (random.nextDouble() * 0.4) - 0.2; // -0.2 ~ 0.2
        }
        
        Emotion emotion = Emotion.builder().type(type).score(score).build();
        return AnalyzedChatMessage.builder()
                .messageId(chat.getMessageId())
                .roomId(chat.getRoomId())
                .content(chat.getContent())
                .emotion(emotion)
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
