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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    @CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
    public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<CompressedChat> chats) {
        if (chats == null || chats.isEmpty()) {
            return Mono.just(List.of());
        }

        if (isProcessing.get()) {
            log.warn("[Ollama] Still processing previous batch. Skipping current batch of {} messages to prevent queue buildup.", chats.size());
            return Mono.just(List.of());
        }
        
        log.info("[Ollama] Requesting analysis for {} compressed messages via local LLM.", chats.size());
        isProcessing.set(true);

        // 1. Build the prompt
        String promptText = buildPrompt(chats);

        OllamaRequest requestDto = OllamaRequest.builder()
                .model(ollamaModel)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(getSystemPrompt()).build(),
                        OllamaMessage.builder().role("user").content(promptText).build()
                ))
                .stream(false)
                .format("json") 
                .build();

        // 2. Call Ollama API with timeout
        return webClient.post()
                .uri(ollamaApiUrl)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .timeout(Duration.ofSeconds(15)) // 15초 타임아웃
                .map(response -> parseOllamaResponse(response, chats))
                .doFinally(signalType -> isProcessing.set(false));
    }

    private String getSystemPrompt() {
        return "You are a Korean chat sentiment analyzer. " +
               "Analyze the vibe: JOY, HOPE, NEUTRAL, SADNESS, ANGER, WONDER, DISGUST. " +
               "Agreement (ㄹㅇ, ㅇㅈ) and reactions (어흐, 캬) should match the previous context's emotion. " +
               "Output ONLY JSON: {\"keywords\": [], \"results\": [{\"messageId\": \"...\", \"type\": \"...\", \"score\": 1.0}]}";
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

        log.info("[Ollama] Raw LLM Response: {}", content);

        try {
            String jsonStr = extractJsonText(content);
            log.info("[Ollama] Extracted JSON: {}", jsonStr);
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

    private String extractJsonText(String text) {
        int firstBrace = text.indexOf('{');
        int firstBracket = text.indexOf('[');
        
        int start;
        if (firstBrace != -1 && firstBracket != -1) start = Math.min(firstBrace, firstBracket);
        else if (firstBrace != -1) start = firstBrace;
        else if (firstBracket != -1) start = firstBracket;
        else return text.trim();

        int lastBrace = text.lastIndexOf('}');
        int lastBracket = text.lastIndexOf(']');
        
        int end = Math.max(lastBrace, lastBracket);
        
        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end + 1);
        }
        
        return text.trim();
    }

    private List<AnalyzedChatMessage> createFallbackList(List<CompressedChat> chats) {
        log.error("[Ollama] Triggering fallback analysis (all NEUTRAL) for {} messages.", chats.size());
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
