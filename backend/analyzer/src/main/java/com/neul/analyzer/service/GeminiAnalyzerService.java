package com.neul.analyzer.service;

import com.neul.analyzer.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.Emotion;
import com.neul.analyzer.optimization.CompressedChat;
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
     * 최적화(필터링+압축)된 채팅 메시지 배치를 분석합니다.
     *
     * <p>
     * 실제 서비스에서는 Vertex AI Gemini API를 호출하며, {@code CompressedChat.count}를
     * 프롬프트에 포함시켜 ("ㅋㅋㅋ (12건)" 형태) 토큰 효율을 극대화합니다.
     *
     * @param chats 최적화된 대표 채팅 메시지 목록
     * @return 각 메시지에 대한 감정 분석 결과 목록
     */
    @CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
    public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<CompressedChat> chats) {
        log.info("[Gemini] Requesting analysis for {} compressed messages (Gemini API 연동 전 시뮬레이션)", chats.size());

        // TODO: 실제 Vertex AI 통신 WebClient 로직 구현 및 파싱
        // - CompressedChat.count를 프롬프트에 포함: "내용 (N건)"
        // - 현재는 임시로 무작위 감정 분석 결과를 생성합니다.

        return Mono.fromCallable(() -> {
            Thread.sleep(100); // API 호출 지연 시뮬레이션
            return chats.stream()
                    .map(this::simulateEmotion)
                    .collect(Collectors.toList());
        });
    }

    /**
     * Fallback 메서드. Gemini API 호출 실패 시 (타임아웃, 서킷브레이커 오픈 등)
     * 배치 전체를 NEUTRAL로 처리합니다.
     */
    public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(List<CompressedChat> chats, Throwable t) {
        log.error("[Gemini] API call failed. CircuitBreaker fallback triggered. Cause: {}", t.getMessage());
        List<AnalyzedChatMessage> fallbackMessages = chats.stream()
                .map(chat -> AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .emotion(Emotion.builder().type("NEUTRAL").score(0.0).build())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        return Mono.just(fallbackMessages);
    }

    private AnalyzedChatMessage simulateEmotion(CompressedChat chat) {
        String text = chat.getContent();
        int r = random.nextInt(10);
        String type;
        double score;

        if (text.contains("재밌") || text.contains("ㅋㅋ") || text.contains("화이팅") || text.contains("대박") || r >= 7) {
            type = "POSITIVE";
            score = 0.5 + (random.nextDouble() * 0.5);
        } else if (text.contains("아쉽") || text.contains("별로") || r <= 1) {
            type = "NEGATIVE";
            score = -0.5 - (random.nextDouble() * 0.5);
        } else {
            type = "NEUTRAL";
            score = (random.nextDouble() * 0.4) - 0.2;
        }

        Emotion emotion = Emotion.builder().type(type).score(score).build();
        return AnalyzedChatMessage.builder()
                .messageId(chat.getRepresentativeId())
                .roomId(chat.getRoomId())
                .content(chat.getContent())
                .emotion(emotion)
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
