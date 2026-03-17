package com.neul.core_api.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import com.neul.core_api.domain.chat.service.StreamRedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class FullPipelineE2ETest extends E2ETestBase {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalyzedChatRepository analyzedChatRepository;

    @Autowired
    private StreamRedisService streamRedisService;

    @Test
    @DisplayName("전체 파이프라인 데이터 흐름 검증 (Kafka -> Core-API -> SSE)")
    @SuppressWarnings("unchecked")
    void testFullPipelineFlow() throws Exception {
        String roomId = "test-room-123";
        String messageId = UUID.randomUUID().toString();
        
        // 0. Redis 세션 활성화 (DB 저장을 위해 필수)
        streamRedisService.setCollectionActive(roomId, true).block();

        AnalyzedChatMessage analyzedMsg = AnalyzedChatMessage.builder()
                .messageId(messageId)
                .roomId(roomId)
                .content("테스트 메시지입니다.")
                .sender("tester")
                .messageType("CHAT")
                .emotionScores(Map.of("JOY", 0.9, "NEUTRAL", 0.1))
                .analyzedAt(LocalDateTime.now())
                .build();

        String json = objectMapper.writeValueAsString(analyzedMsg);

        // 1. SSE 구독 및 검증 시작
        webTestClient.get()
                .uri("/api/v1/channels/" + roomId + "/subscribe")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Map.class)
                .getResponseBody()
                .as(StepVerifier::create)
                .then(() -> {
                    // 2. Kafka 메시지 주입 (지연 발생을 고려하여 then 이후 실행)
                    try {
                        kafkaTemplate.send("analyzed-chat-topic", roomId, json).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .consumeNextWith(event -> {
                    assertThat(event.get("event")).isEqualTo("chat_analyzed");
                    Map<String, Object> data = (Map<String, Object>) event.get("data");
                    assertThat(data.get("messageId")).isEqualTo(messageId);
                    assertThat(data.get("content")).isEqualTo("테스트 메시지입니다.");
                })
                .consumeNextWith(event -> {
                    assertThat(event.get("event")).isEqualTo("stats_update");
                    Map<String, Object> stats = (Map<String, Object>) event.get("data");
                    // Redis Hash HINCRBY 결과는 문자열로 관리될 수 있음
                    assertThat(stats.get("JOY").toString()).contains("0.9");
                })
                .thenCancel()
                .verify();

        // 3. DB 저장 결과 최종 검증
        analyzedChatRepository.findByMessageId(messageId)
                .as(StepVerifier::create)
                .expectNextMatches(chat -> {
                    assertThat(chat.getContent()).isEqualTo("테스트 메시지입니다.");
                    assertThat(chat.getEmotionType()).isEqualTo("JOY");
                    return true;
                })
                .verifyComplete();
    }
}
