package com.neul.analyzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.common.dto.Emotion;
import com.neul.analyzer.dto.ollama.OllamaMessage;
import com.neul.analyzer.dto.ollama.OllamaRequest;
import com.neul.analyzer.dto.ollama.OllamaResponse;
import com.neul.analyzer.optimization.CompressedChat;
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
public class OllamaAnalyzerService {

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
        return "You are an AI specialized in analyzing Korean streaming chat culture. " +
               "CRITICAL: Contextual Sentiment Inheritance. Terms like 'ㄹㅇ', 'ㅇㅈ', '어흐', '캬', 'ㄷㄷ' are NOT always JOY. " +
               "They are agreement/reaction tokens that INHERIT the emotion of the most significant message appearing before them in the same batch. " +
               "If a user says '게임 진짜 못하네' (ANGER) and others say 'ㄹㅇ' (ㄹㅇ), then 'ㄹㅇ' is also ANGER. " +
               "Analyze the batch as a continuous flow. For each message, determine: JOY, HOPE, NEUTRAL, SADNESS, ANGER, WONDER, or DISGUST. " +
               "Also, extract 3-5 high-impact keywords for the entire batch. " +
               "Output EXACTLY a JSON object: {'keywords': [...], 'results': [{'messageId': '...', 'type': '...', 'score': ...}, ...]}";
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
        String content = response.getMessage() != null ? response.getMessage().getContent() : "";
        if (content == null || content.isBlank()) {
            log.warn("[Ollama] Empty content received from LLM.");
            return createFallbackList(originalChats);
        }

        try {
            String jsonStr = extractJsonArray(content);
            List<Map<String, Object>> resultList;
            List<String> keywords = List.of();
            
            if (jsonStr.startsWith("{")) {
                Map<String, Object> root = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
                if (root.containsKey("results")) {
                    Object resultsObj = root.get("results");
                    if (resultsObj instanceof List) {
                        resultList = (List<Map<String, Object>>) resultsObj;
                    } else {
                        resultList = List.of((Map<String, Object>) resultsObj);
                    }
                    if (root.get("keywords") instanceof List) {
                        keywords = (List<String>) root.get("keywords");
                    }
                } else {
                    // Fallback to previous single-object result logic if 'results' key missing
                    resultList = List.of(root);
                }
            } else {
                resultList = objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
            }
            
            final List<String> finalKeywords = keywords;
            Map<String, Emotion> emotionMap = resultList.stream()
                .filter(map -> map != null && map.containsKey("messageId") && map.containsKey("type") && map.containsKey("score"))
                .collect(Collectors.toMap(
                    map -> (String) map.get("messageId"),
                    map -> Emotion.builder()
                            .type((String) map.get("type"))
                            .score(((Number) map.get("score")).doubleValue())
                            .build(),
                    (a, b) -> a
                ));

            return originalChats.stream().map(chat -> {
                Emotion emotion = emotionMap.get(chat.getRepresentativeId());
                return AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .emotion(emotion != null ? emotion : Emotion.builder().type("NEUTRAL").score(0.0).build())
                        .keywords(finalKeywords)
                        .analyzedAt(LocalDateTime.now())
                        .build();
            }).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("[Ollama] Failed to parse LLM response: {}. Error: {}", content, e.getMessage());
            return createFallbackList(originalChats);
        }
    }

    private String extractJsonArray(String text) {
        // Find the first '[' and last ']'
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        
        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end + 1);
        }
        
        // If no brackets found, return as is (readValue will handle errors)
        return text.trim();
    }

    private List<AnalyzedChatMessage> createFallbackList(List<CompressedChat> chats) {
        return chats.stream()
                .map(chat -> AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .emotion(Emotion.builder().type("NEUTRAL").score(0.0).build())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
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
