package com.neul.analyzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
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
import java.util.HashMap;
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

        // 1. Create a logical ID map (Logical ID -> Original ID)
        Map<String, String> logicalIdToOriginalId = new HashMap<>();
        for (int i = 0; i < chats.size(); i++) {
            logicalIdToOriginalId.put(String.valueOf(i + 1), chats.get(i).getRepresentativeId());
        }

        // 2. Build the prompt with logical IDs
        String promptText = buildPrompt(chats, logicalIdToOriginalId);

        OllamaRequest requestDto = OllamaRequest.builder()
                .model(ollamaModel)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(getSystemPrompt()).build(),
                        OllamaMessage.builder().role("user").content(promptText).build()
                ))
                .stream(false)
                .format("json") 
                .options(Map.of(
                        "temperature", 0.4, // Lowered for more deterministic keywords
                        "num_predict", 1024,
                        "top_p", 0.8 // Lowered to focus on high-probability tokens
                ))
                .build();

        // 3. Call Ollama API with timeout
        return webClient.post()
                .uri(ollamaApiUrl)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .timeout(Duration.ofSeconds(60))
                .map(response -> parseOllamaResponse(response, chats, logicalIdToOriginalId))
                .doFinally(signalType -> isProcessing.set(false));
    }

    private String getSystemPrompt() {
        return "You are a professional Korean streaming sentiment analyzer. " +
               "Categories: JOY, HOPE, NEUTRAL, SADNESS, ANGER, WONDER, DISGUST. " +
               "Rules:\n" +
               "1. Extract 3-5 keywords ONLY from the provided chat messages. Do not invent keywords.\n" +
               "2. Reactions like ㄹㅇ, ㅇㅈ, etc. inherit the emotion of previous context.\n" +
               "3. Be decisive. Avoid NEUTRAL if possible.\n" +
               "4. Ensure scores sum to 1.0.\n" +
               "5. Output ONLY JSON. Never return 'null' for keywords.\n\n" +
               "Example:\n" +
               "{\"keywords\": [\"나이스\", \"대박\"], \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.9, ...}}]}";
    }

    private String buildPrompt(List<CompressedChat> chats, Map<String, String> idMap) {
        StringBuilder sb = new StringBuilder("Analyze the following messages:\n");
        Map<String, String> originalToLogical = idMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

        for (CompressedChat chat : chats) {
            String logicalId = originalToLogical.get(chat.getRepresentativeId());
            sb.append(String.format("[%s] %s (%d occurrences)\n",
                    logicalId, chat.getContent(), chat.getCount()));
        }
        return sb.toString();
    }

    private List<AnalyzedChatMessage> parseOllamaResponse(OllamaResponse response, List<CompressedChat> originalChats, Map<String, String> logicalIdToOriginalId) {
        String content = response.getMessage() != null ? response.getMessage().getContent() : "";
        if (content == null || content.isBlank()) {
            log.warn("[Ollama] Empty content received from LLM.");
            return createFallbackList(originalChats);
        }

        log.debug("[Ollama] Raw LLM Response: {}", content);

        try {
            String jsonStr = extractJsonText(content);
            log.debug("[Ollama] Extracted JSON: {}", jsonStr);
            List<Map<String, Object>> resultList;
            List<String> rawKeywords = List.of();
            
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
                        rawKeywords = (List<String>) root.get("keywords");
                    }
                } else {
                    resultList = List.of(root);
                }
            } else {
                resultList = objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
            }

            // Keyowrd Sanitization (Phase 22)
            final List<String> finalKeywords = rawKeywords.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(k -> !k.isEmpty() && !k.equalsIgnoreCase("null"))
                .filter(k -> !k.matches(".*[a-fA-F0-0]{8}-[a-fA-F0-0]{4}-[a-fA-F0-0]{4}.*|.*[a-fA-F0-9]{32}.*")) // ID/Hex filter
                .distinct()
                .collect(Collectors.toList());
            Map<String, Map<String, Double>> emotionMapByLogicalId = resultList.stream()
                .filter(map -> map != null && map.containsKey("messageId") && map.containsKey("scores"))
                .collect(Collectors.toMap(
                    map -> String.valueOf(map.get("messageId")),
                    map -> {
                        Map<String, Object> rawScores = (Map<String, Object>) map.get("scores");
                        Map<String, Double> scores = new HashMap<>();
                        rawScores.forEach((k, v) -> scores.put(k, ((Number) v).doubleValue()));
                        return scores;
                    },
                    (a, b) -> a
                ));

            return originalChats.stream().map(chat -> {
                // 원본 ID를 논리 ID로 변환하여 감정 점수 조회
                String logicalId = logicalIdToOriginalId.entrySet().stream()
                        .filter(e -> e.getValue().equals(chat.getRepresentativeId()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse("-1");

                Map<String, Double> scores = emotionMapByLogicalId.get(logicalId);
                return AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .senderId(chat.getRepresentativeSenderId()) // Preserve senderId
                        .emotionScores(scores != null ? scores : createNeutralScores())
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
                        .senderId(chat.getRepresentativeSenderId()) // Added for Phase 23
                        .emotionScores(createNeutralScores())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
    }

    private Map<String, Double> createNeutralScores() {
        Map<String, Double> scores = new HashMap<>();
        scores.put("JOY", 0.0);
        scores.put("HOPE", 0.0);
        scores.put("NEUTRAL", 1.0);
        scores.put("SADNESS", 0.0);
        scores.put("ANGER", 0.0);
        scores.put("WONDER", 0.0);
        scores.put("DISGUST", 0.0);
        return scores;
    }

    /**
     * Fallback 메서드. Gemini API 호출 실패 시 (타임아웃, 서킷브레이커 오픈 등)
     * 배치 전체를 NEUTRAL로 처리합니다.
     */
    public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(List<CompressedChat> chats, Throwable t) {
        log.error("[Ollama] API call failed. CircuitBreaker fallback triggered. Cause: {}", t.getMessage());
        List<AnalyzedChatMessage> fallbackMessages = chats.stream()
                .map(chat -> AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .senderId(chat.getRepresentativeSenderId()) // Added for Phase 23
                        .emotionScores(createNeutralScores())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        return Mono.just(fallbackMessages);
    }


}
