package com.neul.analyzer.service;

import com.neul.analyzer.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.Emotion;
import com.neul.analyzer.dto.ollama.OllamaMessage;
import com.neul.analyzer.dto.ollama.OllamaRequest;
import com.neul.analyzer.dto.ollama.OllamaResponse;
import com.neul.analyzer.optimization.CompressedChat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiAnalyzerService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.ollama.api-url}")
    private String ollamaApiUrl;

    @Value("${app.ollama.model}")
    private String ollamaModel;

    @CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
    public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<CompressedChat> chats) {
        if (chats == null || chats.isEmpty()) {
            return Mono.just(List.of());
        }
        
        log.info("[Ollama] Requesting analysis for {} compressed messages via local LLM.", chats.size());

        // 1. Build the prompt
        String promptText = buildPrompt(chats);

        OllamaRequest requestDto = OllamaRequest.builder()
                .model(ollamaModel)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(getSystemPrompt()).build(),
                        OllamaMessage.builder().role("user").content(promptText).build()
                ))
                .stream(false)
                .format("json") // Ask Ollama to output valid JSON (works best on supported models)
                .build();

        // 2. Call Ollama API
        return webClient.post()
                .uri(ollamaApiUrl)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .map(response -> parseOllamaResponse(response, chats));
    }

    private String getSystemPrompt() {
        return "You are an AI that analyzes the sentiment of a batch of streaming chat messages in Korean. " +
               "For each message provided by the user, you must determine its overall emotion as either POSITIVE, NEGATIVE, or NEUTRAL. " +
               "Also provide a confidence score between -1.0 and 1.0 (-1.0 being strongly negative, 1.0 being strongly positive, 0.0 being exactly neutral). " +
               "You MUST output exactly a JSON array containing objects with the following keys: " +
               "'messageId' (string), 'type' (string, one of POSITIVE/NEGATIVE/NEUTRAL), 'score' (number). " +
               "Do not output any markdown formatting, markdown code blocks, or extra text. Output ONLY pure JSON.";
    }

    private String buildPrompt(List<CompressedChat> chats) {
        StringBuilder sb = new StringBuilder("Analyze the following messages:\n");
        for (CompressedChat chat : chats) {
            sb.append(String.format("- messageId: %s, content: %s (%d occurrences)\n",
                    chat.getRepresentativeId(), chat.getContent(), chat.getCount()));
        }
        return sb.toString();
    }

    private List<AnalyzedChatMessage> parseOllamaResponse(OllamaResponse response, List<CompressedChat> originalChats) {
        String jsonContent = response.getMessage() != null ? response.getMessage().getContent() : "[]";
        
        try {
            // Sometimes local LLMs might wrap the output in markdown code blocks despite formatting instructions
            if (jsonContent.startsWith("```json")) {
                jsonContent = jsonContent.substring(7);
            }
            if (jsonContent.endsWith("```")) {
                jsonContent = jsonContent.substring(0, jsonContent.length() - 3);
            }
            jsonContent = jsonContent.trim();

            List<Map<String, Object>> parsedList = objectMapper.readValue(jsonContent, new TypeReference<List<Map<String, Object>>>() {});
            
            Map<String, AnalyzedChatMessage> parsedMap = parsedList.stream()
                .filter(map -> map.containsKey("messageId") && map.containsKey("type") && map.containsKey("score"))
                .map(map -> {
                    String messageId = (String) map.get("messageId");
                    String type = (String) map.get("type");
                    double score = ((Number) map.get("score")).doubleValue();
                    return Map.entry(messageId, Emotion.builder().type(type).score(score).build());
                })
                .collect(Collectors.toMap(Map.Entry::getKey, e -> AnalyzedChatMessage.builder()
                        .messageId(e.getKey())
                        .emotion(e.getValue())
                        .analyzedAt(LocalDateTime.now())
                        .build(), (a, b) -> a)); // handle duplicates
            
            // Map the parsed JSON back to the original CompressedChat objects to ensure we don't drop any
            return originalChats.stream().map(chat -> {
                AnalyzedChatMessage parsedEmotion = parsedMap.get(chat.getRepresentativeId());
                if (parsedEmotion != null) {
                    return AnalyzedChatMessage.builder()
                            .messageId(chat.getRepresentativeId())
                            .roomId(chat.getRoomId())
                            .content(chat.getContent())
                            .emotion(parsedEmotion.getEmotion())
                            .analyzedAt(LocalDateTime.now())
                            .build();
                } else {
                    // Fallback to NEUTRAL if the LLM missed this specific ID
                    return AnalyzedChatMessage.builder()
                            .messageId(chat.getRepresentativeId())
                            .roomId(chat.getRoomId())
                            .content(chat.getContent())
                            .emotion(Emotion.builder().type("NEUTRAL").score(0.0).build())
                            .analyzedAt(LocalDateTime.now())
                            .build();
                }
            }).collect(Collectors.toList());

        } catch (JsonProcessingException e) {
            log.error("[Ollama] Failed to parse JSON response: {}", jsonContent, e);
            throw new RuntimeException("JSON parsing failed", e);
        }
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


}
