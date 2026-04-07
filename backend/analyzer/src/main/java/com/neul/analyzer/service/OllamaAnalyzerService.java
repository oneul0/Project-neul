package com.neul.analyzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.analyzer.config.OllamaPromptProperties;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.analyzer.dto.ollama.OllamaMessage;
import com.neul.analyzer.dto.ollama.OllamaRequest;
import com.neul.analyzer.dto.ollama.OllamaResponse;
import com.neul.analyzer.optimization.CompressedChat;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class OllamaAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAnalyzerService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final PromptTemplateService promptTemplateService;
    private final OllamaPromptProperties promptProperties;

    public OllamaAnalyzerService(
            WebClient webClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            PromptTemplateService promptTemplateService,
            OllamaPromptProperties promptProperties
    ) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.promptTemplateService = promptTemplateService;
        this.promptProperties = promptProperties;
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
        String promptText = buildSentimentPrompt(chats, logicalIdToOriginalId);

        OllamaRequest requestDto = OllamaRequest.builder()
                .model(ollamaModel)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(getSentimentSystemPrompt()).build(),
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

    public Mono<HighlightDecision> analyzeHighlight(HighlightPromptPayload payload) {
        try {
            String userPrompt = promptTemplateService.render(promptProperties.getHighlightUser(), Map.ofEntries(
                    Map.entry("videoNo", payload.videoNo()),
                    Map.entry("videoTitle", payload.videoTitle()),
                    Map.entry("videoCategory", payload.videoCategory()),
                    Map.entry("durationSeconds", String.valueOf(payload.durationSeconds())),
                    Map.entry("progressRatio", formatDecimal(payload.progressRatio())),
                    Map.entry("startSeconds", String.valueOf(payload.startSeconds())),
                    Map.entry("endSeconds", String.valueOf(payload.endSeconds())),
                    Map.entry("messageCount", String.valueOf(payload.messageCount())),
                    Map.entry("uniqueUsers", String.valueOf(payload.uniqueUsers())),
                    Map.entry("densityRatio", formatDecimal(payload.densityRatio())),
                    Map.entry("zScore", formatDecimal(payload.zScore())),
                    Map.entry("burstScore", formatDecimal(payload.burstScore())),
                    Map.entry("consensusRatio", formatDecimal(payload.consensusRatio())),
                    Map.entry("peakWindowRatio", formatDecimal(payload.peakWindowRatio())),
                    Map.entry("keywordConcentration", formatDecimal(payload.keywordConcentration())),
                    Map.entry("repeatedRatio", formatDecimal(payload.repeatedRatio())),
                    Map.entry("dominantSenderRatio", formatDecimal(payload.dominantSenderRatio())),
                    Map.entry("goodbyeRatio", formatDecimal(payload.goodbyeRatio())),
                    Map.entry("keywordSummary", payload.keywordSummary()),
                    Map.entry("negativeSignals", payload.negativeSignals()),
                    Map.entry("chatBundle", payload.chatBundle())
            ));

            OllamaRequest requestDto = OllamaRequest.builder()
                    .model(ollamaModel)
                    .messages(List.of(
                            OllamaMessage.builder().role("system").content(getHighlightSystemPrompt()).build(),
                            OllamaMessage.builder().role("user").content(userPrompt).build()
                    ))
                    .stream(false)
                    .format("json")
                    .options(Map.of(
                            "temperature", 0.2,
                            "num_predict", 768,
                            "top_p", 0.8
                    ))
                    .build();

            return webClient.post()
                    .uri(ollamaApiUrl)
                    .bodyValue(requestDto)
                    .retrieve()
                    .bodyToMono(OllamaResponse.class)
                    .timeout(Duration.ofSeconds(45))
                    .map(this::parseHighlightDecision)
                    .onErrorResume(error -> {
                        log.warn("[Ollama] Highlight analysis failed: {}", error.getMessage());
                        return Mono.just(HighlightDecision.fallback("LLM highlight analysis failed, fallback to heuristic ranking."));
                    });
        } catch (Exception error) {
            log.warn("[Ollama] Highlight prompt preparation failed: {}", error.getMessage());
            return Mono.just(HighlightDecision.fallback("LLM highlight prompt preparation failed, fallback to heuristic ranking."));
        }
    }

    private String getSentimentSystemPrompt() {
        return promptTemplateService.render(promptProperties.getSentimentSystem(), Map.of());
    }

    private String getHighlightSystemPrompt() {
        return promptTemplateService.render(promptProperties.getHighlightSystem(), Map.of());
    }

    private String buildSentimentPrompt(List<CompressedChat> chats, Map<String, String> idMap) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> originalToLogical = idMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

        for (CompressedChat chat : chats) {
            String logicalId = originalToLogical.get(chat.getRepresentativeId());
            sb.append(String.format("[%s] %s (%d occurrences)\n",
                    logicalId, chat.getContent(), chat.getCount()));
        }
        return promptTemplateService.render(promptProperties.getSentimentUser(), Map.of(
                "messages", sb.toString().trim()
        ));
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

    private HighlightDecision parseHighlightDecision(OllamaResponse response) {
        String content = response.getMessage() != null ? response.getMessage().getContent() : "";
        if (content == null || content.isBlank()) {
            return HighlightDecision.fallback("LLM returned empty highlight decision.");
        }

        try {
            String jsonStr = extractJsonText(content);
            Map<String, Object> root = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            boolean isHighlight = Boolean.TRUE.equals(root.get("is_highlight"));
            String category = String.valueOf(root.getOrDefault("category", isHighlight ? "소통" : "판단보류")).trim();
            String sceneLabel = String.valueOf(root.getOrDefault("scene_label", category)).trim();
            String summary = String.valueOf(root.getOrDefault("summary", isHighlight ? "하이라이트 후보 구간입니다." : "하이라이트 근거가 약한 구간입니다.")).trim();
            String reasoning = String.valueOf(root.getOrDefault("reasoning", "LLM reasoning not provided.")).trim();
            int intensity = 5;
            Object rawIntensity = root.get("intensity");
            if (rawIntensity instanceof Number number) {
                intensity = Math.max(1, Math.min(10, number.intValue()));
            }
            return new HighlightDecision(isHighlight, category, sceneLabel, summary, intensity, reasoning);
        } catch (Exception e) {
            log.warn("[Ollama] Failed to parse highlight decision: {}", e.getMessage());
            return HighlightDecision.fallback("Failed to parse structured highlight decision.");
        }
    }

    private String formatDecimal(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
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
