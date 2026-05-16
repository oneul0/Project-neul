package com.gak.core_api.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.AnalyzedChatMessage;
import com.gak.core_api.domain.chat.entity.HighlightRecord;
import com.gak.core_api.domain.chat.repository.HighlightRepository;
import com.gak.core_api.domain.chat.service.StreamRedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Highlight E2E 테스트")
class HighlightE2ETest extends E2ETestBase {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StreamRedisService streamRedisService;

    @Autowired
    private HighlightRepository highlightRepository;

    @Test
    @DisplayName("감정 스파이크가 하이라이트 SSE와 DB 저장으로 이어진다")
    @SuppressWarnings("unchecked")
    void testHighlightSpikeFlow() throws Exception {
        String roomId = "highlight-room-" + UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();

        streamRedisService.setCollectionActive(roomId, true).block();

        AnalyzedChatMessage analyzedMsg = AnalyzedChatMessage.builder()
                .messageId(messageId)
                .roomId(roomId)
                .content("이 장면 미쳤다")
                .sender("tester")
                .senderId("user-1")
                .messageType("CHAT")
                .emotionScores(Map.of("JOY", 0.95, "NEUTRAL", 0.05))
                .analyzedAt(LocalDateTime.now())
                .build();

        String json = objectMapper.writeValueAsString(analyzedMsg);

        webTestClient.get()
                .uri("/api/v1/stream/" + roomId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Map.class)
                .getResponseBody()
                .as(StepVerifier::create)
                .then(() -> {
                    try {
                        kafkaTemplate.send("analyzed-chat-topic", roomId, json).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .consumeNextWith(event -> assertThat(event.get("event")).isEqualTo("chat_analyzed"))
                .consumeNextWith(event -> assertThat(event.get("event")).isEqualTo("stats_update"))
                .consumeNextWith(event -> {
                    assertThat(event.get("event")).isEqualTo("highlight_detected");
                    Map<String, Object> data = (Map<String, Object>) event.get("data");
                    assertThat(data.get("roomId")).isEqualTo(roomId);
                    assertThat(data.get("emotionType")).isEqualTo("JOY");
                    assertThat(data.get("topMessage")).isEqualTo("이 장면 미쳤다");
                    assertThat(data.get("liveImageUrl")).isNotNull();
                })
                .thenCancel()
                .verify(Duration.ofSeconds(15));

        highlightRepository.findAll()
                .filter(record -> roomId.equals(record.getRoomId()))
                .single()
                .as(StepVerifier::create)
                .assertNext(this::assertHighlightRecord)
                .verifyComplete();
    }

    private void assertHighlightRecord(HighlightRecord record) {
        assertThat(record.getEmotionType()).isEqualTo("JOY");
        assertThat(record.getPeakScore()).isGreaterThanOrEqualTo(0.9);
        assertThat(record.getTopMessage()).isEqualTo("이 장면 미쳤다");
        assertThat(record.getLiveImageUrl()).isNotBlank();
    }
}
