package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neul.analyzer.config.OllamaPromptProperties;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 
    private MeterRegistry meterRegistry;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // Real SimpleMeterRegistry for testing
        meterRegistry = new SimpleMeterRegistry();

        PromptTemplateService promptTemplateService = new PromptTemplateService(new DefaultResourceLoader());
        OllamaPromptProperties promptProperties = new OllamaPromptProperties();
        
        geminiAnalyzerService = new OllamaAnalyzerService(webClient, objectMapper, meterRegistry, promptTemplateService, promptProperties);
        
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
                "{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.8, \"NEUTRAL\": 0.2}}," +
                "{\"messageId\": \"2\", \"scores\": {\"ANGER\": 0.5, \"SADNESS\": 0.5}}" +
                "]}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .model("gemma:2b")
                .message(OllamaMessage.builder().role("assistant").content(mockJsonResponse).build())
                .done(true)
                .build();

        // WebClient Mocking
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
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
        String mockJsonResponse = "{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 1.0}}]}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
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

        String mockJsonResponse = "```json\n{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"NEUTRAL\": 1.0}}]}\n```";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
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
                "{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.9}}]}\n" +
                "I hope this helps!";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockResponseContent).build())
                .build();

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
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
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
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

    @Test
    @DisplayName("하이라이트 분석 응답을 정상 파싱하고 프롬프트 값을 요청에 담는다")
    void analyzeHighlight_Success() {
        HighlightPromptPayload payload = new HighlightPromptPayload(
                "video-1",
                "테스트 VOD",
                "게임",
                3600,
                0.25,
                30,
                60,
                24,
                12,
                2.35,
                1.75,
                4.20,
                0.42,
                2.80,
                0.31,
                0.12,
                0.21,
                0.00,
                "- '한타' (3회)",
                "- 대표 채팅: 미쳤다",
                "- 미쳤다 (x3)"
        );

        String mockJsonResponse = "{" +
                "\"is_highlight\": true," +
                "\"category\": \"슈퍼플레이\"," +
                "\"scene_label\": \"클러치\"," +
                "\"summary\": \"한 줄 요약\"," +
                "\"intensity\": 9," +
                "\"reasoning\": \"근거 설명\"" +
                "}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        mockOllamaResponse(Mono.just(mockResponse));

        StepVerifier.create(geminiAnalyzerService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isTrue();
                    assertThat(result.category()).isEqualTo("슈퍼플레이");
                    assertThat(result.sceneLabel()).isEqualTo("클러치");
                    assertThat(result.summary()).isEqualTo("한 줄 요약");
                    assertThat(result.intensity()).isEqualTo(9);
                    assertThat(result.reasoning()).isEqualTo("근거 설명");
                })
                .verifyComplete();

        ArgumentCaptor<OllamaRequest> captor = ArgumentCaptor.forClass(OllamaRequest.class);
        verify(requestBodySpec).bodyValue(captor.capture());
        OllamaRequest capturedRequest = captor.getValue();
        assertThat(capturedRequest.getMessages()).hasSize(2);
        assertThat(capturedRequest.getMessages().get(0).getContent()).contains("전문 편집자");
        assertThat(capturedRequest.getMessages().get(1).getContent())
                .contains("video-1")
                .contains("테스트 VOD")
                .contains("게임")
                .contains("30초 ~ 60초")
                .contains("평소 대비 2.35배")
                .contains("0.42")
                .contains("한타")
                .contains("미쳤다");
    }

    @Test
    @DisplayName("하이라이트 응답 필드가 비어 있으면 기본값을 채우고 intensity를 보정한다")
    void analyzeHighlight_DefaultsAndClamp() {
        HighlightPromptPayload payload = new HighlightPromptPayload(
                "video-2", "제목 없음", "카테고리 없음", 1800, 0.0,
                0, 30, 10, 5, 1.0, 0.4, 1.2, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0,
                "- 없음", "- 없음", "- 없음"
        );

        String mockJsonResponse = "{" +
                "\"is_highlight\": true," +
                "\"intensity\": 99" +
                "}";

        OllamaResponse mockResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content(mockJsonResponse).build())
                .build();

        mockOllamaResponse(Mono.just(mockResponse));

        StepVerifier.create(geminiAnalyzerService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isTrue();
                    assertThat(result.category()).isEqualTo("소통");
                    assertThat(result.sceneLabel()).isEqualTo("소통");
                    assertThat(result.summary()).isEqualTo("하이라이트 후보 구간입니다.");
                    assertThat(result.intensity()).isEqualTo(10);
                    assertThat(result.reasoning()).isEqualTo("LLM reasoning not provided.");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("하이라이트 응답이 비었거나 손상되면 fallback 결정을 반환한다")
    void analyzeHighlight_BlankOrMalformed_Fallback() {
        HighlightPromptPayload payload = new HighlightPromptPayload(
                "video-3", "제목 없음", "카테고리 없음", 1800, 0.05,
                60, 90, 15, 8, 1.4, 0.9, 2.1, 0.2, 1.8, 0.2, 0.2, 0.1, 0.0,
                "- 없음", "- 없음", "- 없음"
        );

        OllamaResponse blankResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content("   ").build())
                .build();

        mockOllamaResponse(Mono.just(blankResponse));

        StepVerifier.create(geminiAnalyzerService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.category()).isEqualTo("판단보류");
                    assertThat(result.sceneLabel()).isEqualTo("판단보류");
                    assertThat(result.summary()).isEqualTo("하이라이트 근거가 부족합니다.");
                })
                .verifyComplete();

        OllamaResponse malformedResponse = OllamaResponse.builder()
                .message(OllamaMessage.builder().content("not-json").build())
                .build();

        reset(webClient, requestBodyUriSpec, requestBodySpec, requestHeadersSpec, responseSpec);
        mockOllamaResponse(Mono.just(malformedResponse));

        StepVerifier.create(geminiAnalyzerService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.reasoning()).contains("Failed to parse structured highlight decision");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("하이라이트 프롬프트 로딩이 실패해도 휴리스틱 fallback으로 안전하게 복귀한다")
    void analyzeHighlight_PromptLoadFailure_Fallback() {
        HighlightPromptPayload payload = new HighlightPromptPayload(
                "video-4", "제목 없음", "카테고리 없음", 1800, 0.08,
                90, 120, 18, 11, 1.9, 1.2, 2.8, 0.05, 1.4, 0.25, 0.05, 0.08, 0.0,
                "- 없음", "- 없음", "- 없음"
        );

        OllamaPromptProperties brokenPromptProperties = new OllamaPromptProperties();
        brokenPromptProperties.setHighlightSystem("classpath:prompts/missing-system.txt");
        brokenPromptProperties.setHighlightUser("classpath:prompts/missing-user.txt");
        PromptTemplateService promptTemplateService = new PromptTemplateService(new DefaultResourceLoader());
        OllamaAnalyzerService brokenService = new OllamaAnalyzerService(webClient, objectMapper, meterRegistry, promptTemplateService, brokenPromptProperties);
        ReflectionTestUtils.setField(brokenService, "ollamaApiUrl", "http://localhost:11434/api/chat");
        ReflectionTestUtils.setField(brokenService, "ollamaModel", "gemma:2b");

        StepVerifier.create(brokenService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.reasoning()).contains("prompt preparation failed");
                })
                .verifyComplete();

        verifyNoInteractions(webClient);
    }

    private void mockOllamaResponse(Mono<OllamaResponse> responseMono) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(OllamaResponse.class)).thenReturn(responseMono);
    }
}
