package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.ollama.OllamaMessage;
import com.neul.analyzer.dto.ollama.OllamaRequest;
import com.neul.analyzer.dto.ollama.OllamaResponse;
import com.neul.analyzer.optimization.CompressedChat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OllamaAnalyzerServiceTest {

    private OllamaAnalyzerService geminiAnalyzerService;

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        geminiAnalyzerService = new OllamaAnalyzerService(webClient, objectMapper);
        
        ReflectionTestUtils.setField(geminiAnalyzerService, "ollamaApiUrl", "http://localhost:11434/api/chat");
        ReflectionTestUtils.setField(geminiAnalyzerService, "ollamaModel", "gemma:2b");
    }

    @Test
    @DisplayName("Ollama API를 통해 채팅 배치를 정상으로 분석하고 결과를 파싱한다")
    void analyzeBatch_Success() {
        // given
        List<CompressedChat> chats = List.of(
                CompressedChat.builder().representativeId("msg-1").roomId("room-1").content("좋은 아침이에요").count(5).build(),
                CompressedChat.builder().representativeId("msg-2").roomId("room-1").content("졸려요").count(3).build()
        );

        String mockJsonResponse = "{" +
                "\"keywords\": [], \"results\": [" +
                "{\"messageId\": \"msg-1\", \"scores\": {\"JOY\": 0.8, \"NEUTRAL\": 0.2}}," +
                "{\"messageId\": \"msg-2\", \"scores\": {\"ANGER\": 0.5, \"SADNESS\": 0.5}}" +
                "]}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .model("gemma:2b")
                .message(OllamaMessage.builder().role("assistant").content(mockJsonResponse).build())
                .done(true)
                .build();

        // WebClient Mocking
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(Mono.just(mockResponse));

        // when
        Mono<List<AnalyzedChatMessage>> resultMono = geminiAnalyzerService.analyzeBatch(chats);

        // then
        StepVerifier.create(resultMono)
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    
                    AnalyzedChatMessage msg1 = results.stream().filter(r -> r.getMessageId().equals("msg-1")).findFirst().get();
                    assertThat(msg1.getEmotionScores().get("JOY")).isEqualTo(0.8);
                    assertThat(msg1.getEmotionScores().get("NEUTRAL")).isEqualTo(0.2);
                    assertThat(msg1.getContent()).isEqualTo("좋은 아침이에요");

                    AnalyzedChatMessage msg2 = results.stream().filter(r -> r.getMessageId().equals("msg-2")).findFirst().get();
                    assertThat(msg2.getEmotionScores().get("ANGER")).isEqualTo(0.5);
                    assertThat(msg2.getEmotionScores().get("SADNESS")).isEqualTo(0.5);
                })
                .verifyComplete();

        // Verify request DTO
        ArgumentCaptor<OllamaRequest> captor = ArgumentCaptor.forClass(OllamaRequest.class);
        verify(requestBodySpec).bodyValue(captor.capture());
        OllamaRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getModel()).isEqualTo("gemma:2b");
        assertThat(capturedRequest.getMessages()).hasSize(2); // system + user
    }

    @Test
    @DisplayName("API 응답에 일부 메시지가 누락된 경우 NEUTRAL로 폴백 처리한다")
    void analyzeBatch_PartialMissingResponse() {
        // given
        List<CompressedChat> chats = List.of(
                CompressedChat.builder().representativeId("msg-1").roomId("room-1").content("테스트1").count(1).build(),
                CompressedChat.builder().representativeId("msg-2").roomId("room-1").content("테스트2").count(1).build()
        );

        // msg-2에 대한 분석 정보가 JSON에 없는 경우
        String mockJsonResponse = "{\"keywords\": [], \"results\": [{\"messageId\": \"msg-1\", \"scores\": {\"JOY\": 1.0}}]}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(geminiAnalyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    AnalyzedChatMessage msg2 = results.stream().filter(r -> r.getMessageId().equals("msg-2")).findFirst().get();
                    assertThat(msg2.getEmotionScores().get("NEUTRAL")).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("JSON 응답에 Markdown 코드 블록이 포함되어 있어도 정상 파싱한다")
    void analyzeBatch_WithMarkdownCodeBlock() {
        // given
        List<CompressedChat> chats = List.of(
                CompressedChat.builder().representativeId("msg-1").roomId("room-1").content("테스트").count(1).build()
        );

        String mockJsonResponse = "```json\n{\"keywords\": [], \"results\": [{\"messageId\": \"msg-1\", \"scores\": {\"NEUTRAL\": 1.0}}]}\n```";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(geminiAnalyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results.get(0).getEmotionScores().get("NEUTRAL")).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("응답에 JSON 외에 불필요한 텍스트가 섞여 있어도 대괄호를 기준으로 JSON만 추출하여 파싱한다")
    void analyzeBatch_WithExtraText() {
        // given
        List<CompressedChat> chats = List.of(
                CompressedChat.builder().representativeId("msg-1").roomId("room-1").content("테스트").count(1).build()
        );

        String mockResponseContent = "Here is your analysis result:\n" +
                "{\"keywords\": [], \"results\": [{\"messageId\": \"msg-1\", \"scores\": {\"JOY\": 0.9}}]}\n" +
                "I hope this helps!";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockResponseContent).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(geminiAnalyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).getEmotionScores().get("JOY")).isEqualTo(0.9);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("응답 JSON이 완전히 깨진 경우 전체를 NEUTRAL로 폴백 처리한다")
    void analyzeBatch_MalformedJson_Fallback() {
        // given
        List<CompressedChat> chats = List.of(
                CompressedChat.builder().representativeId("msg-1").roomId("room-1").content("테스트").count(1).build()
        );

        String malformedJson = "[{\"messageId\": \"msg-1\", \"type\": \"POSITIVE\" ... (broken) ]";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(malformedJson).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(geminiAnalyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).getEmotionScores().get("NEUTRAL")).isEqualTo(1.0);
                })
                .verifyComplete();
    }
}
