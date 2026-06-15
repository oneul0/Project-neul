package com.gak.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gak.analyzer.optimization.ChatOptimizer;
import com.gak.analyzer.optimization.CompressedChat;
import com.gak.analyzer.optimization.OptimizedBatch;
import com.gak.common.dto.AnalyzedChatMessage;
import com.gak.common.dto.RawChatBatch;
import com.gak.common.dto.RawChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAnalysisProcessorTest {

    private static final String OUTPUT_TOPIC = "analyzed-chat-topic";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OllamaAnalyzerService analyzerService;

    @Mock
    private HeuristicSentimentAnalyzer heuristicAnalyzer;

    @Mock
    private ChatOptimizer chatOptimizer;

    private ObjectMapper objectMapper;
    private ChatAnalysisProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        processor = new ChatAnalysisProcessor(
                kafkaTemplate,
                analyzerService,
                heuristicAnalyzer,
                objectMapper,
                chatOptimizer,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("ambiguous messages are published once through LLM or heuristic fallback")
    void processBatch_PublishesAmbiguousMessagesOnce() throws Exception {
        RawChatMessage clear = raw("msg-clear", "clear");
        RawChatMessage ambiguous1 = raw("msg-amb-1", "ambiguous one");
        RawChatMessage ambiguous2 = raw("msg-amb-2", "ambiguous two");

        when(heuristicAnalyzer.analyze(any(RawChatMessage.class))).thenAnswer(invocation -> {
            RawChatMessage message = invocation.getArgument(0);
            boolean ambiguous = message.getContent().startsWith("ambiguous");
            return analyzed(message.getMessageId(), message.getRoomId(), message.getContent(), ambiguous, "heuristic");
        });
        when(chatOptimizer.optimize(any())).thenReturn(OptimizedBatch.builder()
                .compressedChats(List.of(compressed("msg-amb-1", "ambiguous one")))
                .originalCount(2)
                .filteredCount(0)
                .compressionRatio(50.0)
                .build());
        when(analyzerService.analyzeBatch(any())).thenReturn(Mono.just(List.of(
                analyzed("msg-amb-1", "room-1", "ambiguous one", false, "llm")
        )));

        processor.processBatch(List.of(toJson(batch(clear, ambiguous1, ambiguous2))));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(3)).send(eq(OUTPUT_TOPIC), eq("room-1"), payloadCaptor.capture());

        List<AnalyzedChatMessage> published = payloadCaptor.getAllValues().stream()
                .map(this::readAnalyzed)
                .toList();
        assertThat(published).extracting(AnalyzedChatMessage::getMessageId)
                .containsExactlyInAnyOrder("msg-clear", "msg-amb-1", "msg-amb-2");
        assertThat(published).extracting(AnalyzedChatMessage::getMessageId)
                .doesNotHaveDuplicates();
        assertThat(find(published, "msg-amb-1").getKeywords()).containsExactly("llm");
        assertThat(find(published, "msg-amb-2").getKeywords()).containsExactly("heuristic");
    }

    @Test
    @DisplayName("ambiguous messages fall back when LLM returns no result")
    void processBatch_FallsBackWhenLlmReturnsEmpty() throws Exception {
        RawChatMessage ambiguous = raw("msg-amb", "ambiguous");

        when(heuristicAnalyzer.analyze(any(RawChatMessage.class))).thenAnswer(invocation -> {
            RawChatMessage message = invocation.getArgument(0);
            return analyzed(message.getMessageId(), message.getRoomId(), message.getContent(), true, "heuristic");
        });
        when(chatOptimizer.optimize(any())).thenReturn(OptimizedBatch.builder()
                .compressedChats(List.of(compressed("msg-amb", "ambiguous")))
                .originalCount(1)
                .filteredCount(0)
                .compressionRatio(0.0)
                .build());
        when(analyzerService.analyzeBatch(any())).thenReturn(Mono.just(List.of()));

        processor.processBatch(List.of(toJson(batch(ambiguous))));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(OUTPUT_TOPIC), eq("room-1"), payloadCaptor.capture());

        AnalyzedChatMessage published = readAnalyzed(payloadCaptor.getValue());
        assertThat(published.getMessageId()).isEqualTo("msg-amb");
        assertThat(published.getKeywords()).containsExactly("heuristic");
    }

    private RawChatBatch batch(RawChatMessage... messages) {
        return RawChatBatch.builder()
                .roomId("room-1")
                .batchTime(LocalDateTime.now())
                .messages(List.of(messages))
                .build();
    }

    private RawChatMessage raw(String messageId, String content) {
        return RawChatMessage.builder()
                .messageId(messageId)
                .roomId("room-1")
                .messageType("CHAT")
                .sender("sender")
                .senderId("sender-id")
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private AnalyzedChatMessage analyzed(String messageId, String roomId, String content, boolean ambiguous, String source) {
        return AnalyzedChatMessage.builder()
                .messageId(messageId)
                .roomId(roomId)
                .messageType("CHAT")
                .content(content)
                .emotionScores(Map.of("NEUTRAL", 1.0))
                .keywords(List.of(source))
                .isAmbiguous(ambiguous)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private CompressedChat compressed(String messageId, String content) {
        return CompressedChat.builder()
                .representativeId(messageId)
                .representativeSenderId("sender-id")
                .roomId("room-1")
                .content(content)
                .count(1)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private AnalyzedChatMessage readAnalyzed(String json) {
        try {
            return objectMapper.readValue(json, AnalyzedChatMessage.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private AnalyzedChatMessage find(List<AnalyzedChatMessage> messages, String messageId) {
        return messages.stream()
                .filter(message -> messageId.equals(message.getMessageId()))
                .findFirst()
                .orElseThrow();
    }
}
