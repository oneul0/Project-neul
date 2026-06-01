package com.gak.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gak.analyzer.config.OllamaPromptProperties;
import com.gak.analyzer.llm.ChatLlmClient;
import com.gak.analyzer.optimization.CompressedChat;
import com.gak.common.dto.AnalyzedChatMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllamaAnalyzerServiceTest {

    private OllamaAnalyzerService analyzerService;

    @Mock
    private ChatLlmClient chatClient;

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
    private MeterRegistry meterRegistry;
    private PromptTemplateService promptTemplateService;
    private OllamaPromptProperties promptProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        promptTemplateService = new PromptTemplateService(new DefaultResourceLoader());
        promptProperties = new OllamaPromptProperties();

        analyzerService = new OllamaAnalyzerService(
                chatClient,
                webClient,
                objectMapper,
                meterRegistry,
                promptTemplateService,
                promptProperties
        );
        setCoreApiFields(analyzerService);
    }

    @Test
    @DisplayName("analyzeBatch parses structured LLM results")
    void analyzeBatch_Success() {
        List<CompressedChat> chats = List.of(
                compressed("msg-1", "room-1", "nice play", 5),
                compressed("msg-2", "room-1", "too bad", 3)
        );
        String response = "{" +
                "\"keywords\": [], \"results\": [" +
                "{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.8, \"NEUTRAL\": 0.2}}," +
                "{\"messageId\": \"2\", \"scores\": {\"ANGER\": 0.5, \"SADNESS\": 0.5}}" +
                "]}";
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just(response));

        StepVerifier.create(analyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(2);

                    AnalyzedChatMessage msg1 = findById(results, "msg-1");
                    assertThat(msg1.getEmotionScores().get("JOY")).isEqualTo(0.8);
                    assertThat(msg1.getEmotionScores().get("NEUTRAL")).isEqualTo(0.2);
                    assertThat(msg1.getContent()).isEqualTo("nice play");

                    AnalyzedChatMessage msg2 = findById(results, "msg-2");
                    assertThat(msg2.getEmotionScores().get("ANGER")).isEqualTo(0.5);
                    assertThat(msg2.getEmotionScores().get("SADNESS")).isEqualTo(0.5);
                })
                .verifyComplete();

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient).chat(anyString(), userPrompt.capture(), anyDouble(), anyInt());
        assertThat(userPrompt.getValue()).contains("[1] nice play (5 occurrences)");
        assertThat(userPrompt.getValue()).contains("[2] too bad (3 occurrences)");
    }

    @Test
    @DisplayName("analyzeBatch fills missing message results with NEUTRAL")
    void analyzeBatch_PartialMissingResponse() {
        List<CompressedChat> chats = List.of(
                compressed("msg-1", "room-1", "first", 1),
                compressed("msg-2", "room-1", "second", 1)
        );
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 1.0}}]}"));

        StepVerifier.create(analyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(2);
                    assertThat(findById(results, "msg-2").getEmotionScores().get("NEUTRAL")).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("analyzeBatch extracts JSON from markdown or surrounding text")
    void analyzeBatch_ExtractsJsonText() {
        List<CompressedChat> chats = List.of(compressed("msg-1", "room-1", "hello", 1));
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("```json\n{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"NEUTRAL\": 1.0}}]}\n```"));

        StepVerifier.create(analyzerService.analyzeBatch(chats))
                .assertNext(results -> assertThat(results.get(0).getEmotionScores().get("NEUTRAL")).isEqualTo(1.0))
                .verifyComplete();

        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("Analysis:\n{\"keywords\": [], \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.9}}]}\nDone."));

        StepVerifier.create(analyzerService.analyzeBatch(chats))
                .assertNext(results -> assertThat(results.get(0).getEmotionScores().get("JOY")).isEqualTo(0.9))
                .verifyComplete();
    }

    @Test
    @DisplayName("analyzeBatch falls back to NEUTRAL on malformed JSON")
    void analyzeBatch_MalformedJson_Fallback() {
        List<CompressedChat> chats = List.of(compressed("msg-1", "room-1", "hello", 1));
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("[{\"messageId\": \"1\", \"type\": \"POSITIVE\" ... (broken) ]"));

        StepVerifier.create(analyzerService.analyzeBatch(chats))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).getEmotionScores().get("NEUTRAL")).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("analyzeHighlight fetches few-shot examples and parses decision")
    void analyzeHighlight_Success() {
        HighlightPromptPayload payload = highlightPayload();
        mockFewShotExamples("- similar highlight");
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{" +
                        "\"is_highlight\": true," +
                        "\"category\": \"SUPER_PLAY\"," +
                        "\"scene_label\": \"Clutch moment\"," +
                        "\"summary\": \"Great turnaround\"," +
                        "\"intensity\": 9," +
                        "\"reasoning\": \"chat density and hype are high\"" +
                        "}"));

        StepVerifier.create(analyzerService.analyzeHighlight(payload))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isTrue();
                    assertThat(result.category()).isEqualTo("SUPER_PLAY");
                    assertThat(result.sceneLabel()).isEqualTo("Clutch moment");
                    assertThat(result.summary()).isEqualTo("Great turnaround");
                    assertThat(result.intensity()).isEqualTo(9);
                    assertThat(result.reasoning()).isEqualTo("chat density and hype are high");
                })
                .verifyComplete();

        verify(responseSpec).bodyToMono(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient).chat(anyString(), userPrompt.capture(), anyDouble(), anyInt());
        assertThat(userPrompt.getValue()).contains("Sample VOD");
        assertThat(userPrompt.getValue()).contains("hype=0.55");
        assertThat(userPrompt.getValue()).contains("- similar highlight");
    }

    @Test
    @DisplayName("analyzeHighlight clamps intensity and supplies defaults")
    void analyzeHighlight_DefaultsAndClamp() {
        mockFewShotExamples("");
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("{\"is_highlight\": true, \"intensity\": 99}"));

        StepVerifier.create(analyzerService.analyzeHighlight(highlightPayload()))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isTrue();
                    assertThat(result.intensity()).isEqualTo(10);
                    assertThat(result.reasoning()).isEqualTo("LLM reasoning not provided.");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("analyzeHighlight falls back on blank or malformed responses")
    void analyzeHighlight_BlankOrMalformed_Fallback() {
        mockFewShotExamples("");
        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("   "));

        StepVerifier.create(analyzerService.analyzeHighlight(highlightPayload()))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.reasoning()).contains("empty highlight decision");
                })
                .verifyComplete();

        when(chatClient.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(Mono.just("not-json"));

        StepVerifier.create(analyzerService.analyzeHighlight(highlightPayload()))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.reasoning()).contains("Failed to parse structured highlight decision");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("analyzeHighlight falls back when prompt rendering fails")
    void analyzeHighlight_PromptLoadFailure_Fallback() {
        OllamaPromptProperties brokenPromptProperties = new OllamaPromptProperties();
        brokenPromptProperties.setHighlightSystem("classpath:prompts/missing-system.txt");
        brokenPromptProperties.setHighlightUser("classpath:prompts/missing-user.txt");
        OllamaAnalyzerService brokenService = new OllamaAnalyzerService(
                chatClient,
                webClient,
                objectMapper,
                meterRegistry,
                promptTemplateService,
                brokenPromptProperties
        );
        setCoreApiFields(brokenService);
        mockFewShotExamples("");

        StepVerifier.create(brokenService.analyzeHighlight(highlightPayload()))
                .assertNext(result -> {
                    assertThat(result.isHighlight()).isFalse();
                    assertThat(result.reasoning()).contains("prompt preparation failed");
                })
                .verifyComplete();

        verify(chatClient, never()).chat(anyString(), anyString(), anyDouble(), anyInt());
    }

    private void mockFewShotExamples(String response) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), any(String[].class));
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(response));
    }

    private void setCoreApiFields(OllamaAnalyzerService service) {
        ReflectionTestUtils.setField(service, "coreApiBaseUrl", "http://core-api");
        ReflectionTestUtils.setField(service, "internalApiSecret", "test-secret");
    }

    private CompressedChat compressed(String messageId, String roomId, String content, int count) {
        return CompressedChat.builder()
                .representativeId(messageId)
                .representativeSenderId("sender-" + messageId)
                .roomId(roomId)
                .content(content)
                .count(count)
                .build();
    }

    private AnalyzedChatMessage findById(List<AnalyzedChatMessage> results, String messageId) {
        return results.stream()
                .filter(result -> messageId.equals(result.getMessageId()))
                .findFirst()
                .orElseThrow();
    }

    private HighlightPromptPayload highlightPayload() {
        return new HighlightPromptPayload(
                "video-1",
                "Sample VOD",
                "Game",
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
                "- clutch (3)",
                "- none",
                "- amazing play (x3)",
                "",
                0.15,
                0.55,
                0.20,
                0.10,
                0.50,
                "hype"
        );
    }
}
