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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OllamaAnalyzerService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OllamaAnalyzerService(WebClient webClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

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
                        "temperature", 0.4,
                        "num_predict", 1024,
                        "top_p", 0.8
                ))
                .build();

        // 3. Call Ollama API with timeout & metrics
        Timer.Sample sample = (meterRegistry != null) ? Timer.start(meterRegistry) : null;
        if (meterRegistry != null) {
            meterRegistry.counter("neul.llm.api.calls.total").increment();
        }

        return webClient.post()
                .uri(ollamaApiUrl)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .timeout(Duration.ofSeconds(60))
                .map(response -> {
                    if (sample != null && meterRegistry != null) {
                        sample.stop(meterRegistry.timer("neul.llm.api.latency"));
                    }
                    return parseOllamaResponse(response, chats, logicalIdToOriginalId);
                })
                .doFinally(signalType -> isProcessing.set(false));
    }

    private String getSystemPrompt() {
        return "You are a professional Korean streaming sentiment and keyword analyzer. " +
               "Categories: JOY, HOPE, NEUTRAL, SADNESS, ANGER, WONDER, DISGUST. " +
               "Rules:\n" +
               "1. Extract 3-5 keywords ONLY from the provided chat messages. Do not invent keywords.\n" +
               "2. For each keyword, provide a 'representativeName' (a normalized form to group synonyms, e.g., '킹아' and 'KINGA' -> 'KINGA').\n" +
               "3. Reactions like ㄹㅇ, ㅇㅈ, etc. inherit the emotion of previous context.\n" +
               "4. Be decisive. Avoid NEUTRAL if possible.\n" +
               "5. Ensure scores sum to 1.0.\n" +
               "6. Output ONLY JSON.\n\n" +
               "Example:\n" +
               "{\n" +
               "  \"keywords\": [\n" +
               "    {\"text\": \"나이스\", \"representativeName\": \"나이스\"},\n" +
               "    {\"text\": \"킹아\", \"representativeName\": \"KINGA\"}\n" +
               "  ],\n" +
               "  \"results\": [{\"messageId\": \"1\", \"scores\": {\"JOY\": 0.9, ...}}]\n" +
               "}";
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

        log.info("[Ollama] Raw LLM Response: {}", content);

        try {
            String jsonStr = extractJsonText(content);
            log.info("[Ollama] Extracted JSON: {}", jsonStr);
            List<Map<String, Object>> resultList;
            List<Map<String, String>> rawKeywords = List.of();
            
            if (jsonStr.startsWith("{")) {
                Map<String, Object> root = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
                if (root.containsKey("results")) {
                    Object resultsObj = root.get("results");
                    if (resultsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> list = (List<Map<String, Object>>) resultsObj;
                        resultList = list;
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mapObj = (Map<String, Object>) resultsObj;
                        resultList = List.of(mapObj);
                    }
                    if (root.get("keywords") instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> keywords = (List<Map<String, String>>) root.get("keywords");
                        rawKeywords = keywords;
                    }
                } else {
                    resultList = List.of(root);
                }
            } else {
                resultList = objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
            }

            // Keyword Mapping (Phase 24)
            final Map<String, String> keywordToGroup = new HashMap<>();
            final List<String> finalKeywords = new ArrayList<>();
            
            for (Map<String, String> kwMap : rawKeywords) {
                String text = kwMap.get("text");
                String group = kwMap.get("representativeName");
                if (text != null && !text.isBlank()) {
                    text = text.trim();
                    finalKeywords.add(text);
                    if (group != null && !group.isBlank()) {
                        keywordToGroup.put(text, group.trim());
                    }
                }
            }
            Map<String, Map<String, Double>> emotionMapByLogicalId = resultList.stream()
                .filter(map -> map != null && map.containsKey("messageId") && map.containsKey("scores"))
                .collect(Collectors.toMap(
                    map -> String.valueOf(map.get("messageId")),
                    map -> {
                        @SuppressWarnings("unchecked")
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
                        .keywordGroups(!keywordToGroup.isEmpty() ? keywordToGroup : null)
                        .timestamp(chat.getTimestamp())
                        .analyzedAt(LocalDateTime.now())
                        .build();
            }).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("[Ollama] Failed to parse LLM response: {}. Error: {}", content, e.getMessage());
            return createFallbackList(originalChats);
        }
    }

    private String extractJsonText(String text) {
        if (text == null) return "";
        
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
            String result = text.substring(start, end + 1);
            // Remove markdown code block markers if accidentally included
            return result.replace("```json", "").replace("```", "").trim();
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
                        .timestamp(chat.getTimestamp())
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
                        .timestamp(chat.getTimestamp())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        return Mono.just(fallbackMessages);
    }


}
