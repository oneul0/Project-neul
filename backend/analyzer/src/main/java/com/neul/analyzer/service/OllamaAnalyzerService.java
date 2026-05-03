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
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
public class OllamaAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAnalyzerService.class);

    // ─── 입력 가드레일 상수 ───────────────────────────────────────────────────
    private static final int MAX_BATCH_SIZE = 30;
    private static final int MAX_INPUT_CHARS = 3000;

    // ─── 출력 가드레일 상수 ───────────────────────────────────────────────────
    private static final Set<String> VALID_EMOTIONS =
            Set.of("JOY", "HOPE", "NEUTRAL", "SADNESS", "ANGER", "WONDER", "DISGUST");

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

    @Value("${app.core-api.base-url}")
    private String coreApiBaseUrl;

    // ─── 동시성 가드레일: AtomicBoolean 대신 Semaphore 사용 ──────────────────
    // Semaphore(1): 슬롯 1개. 스킵 시 neul.llm.batch.skipped 카운터로 관측 가능.
    // 향후 병렬 분석이 필요하면 Semaphore(N)으로 확장 가능.
    private final Semaphore llmSlot = new Semaphore(1);

    @CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
    public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<CompressedChat> chats) {
        if (chats == null || chats.isEmpty()) {
            return Mono.just(List.of());
        }

        // [가드레일 입력] 배치 크기 및 총 문자 수 상한 적용
        List<CompressedChat> capped = applyInputGuardrails(chats);
        if (capped.isEmpty()) {
            return Mono.just(List.of());
        }

        if (!llmSlot.tryAcquire()) {
            log.warn("[Ollama] LLM slot busy. Skipping batch of {} chats.", chats.size());
            recordCount("neul.llm.batch.skipped");
            return Mono.just(List.of());
        }

        return doAnalyzeBatch(capped)
                .doFinally(ignored -> llmSlot.release());
    }

    private Mono<List<AnalyzedChatMessage>> doAnalyzeBatch(List<CompressedChat> chats) {
        log.info("[Ollama] Requesting analysis for {} compressed messages via local LLM.", chats.size());

        Map<String, String> originalIdToLogicalId = new HashMap<>();
        for (int i = 0; i < chats.size(); i++) {
            originalIdToLogicalId.put(chats.get(i).getRepresentativeId(), String.valueOf(i + 1));
        }

        String promptText = buildSentimentPrompt(chats, originalIdToLogicalId);
        OllamaRequest requestDto = buildOllamaRequest(
                getSentimentSystemPrompt(),
                promptText,
                0.4,
                1024
        );

        Timer.Sample sample = (meterRegistry != null) ? Timer.start(meterRegistry) : null;
        recordCount("neul.llm.api.calls.total");

        return webClient.post()
                .uri(ollamaApiUrl)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                // [가드레일 타임아웃] 배치 크기에 비례한 동적 타임아웃
                .timeout(computeTimeout(chats.size()))
                .map(response -> {
                    if (sample != null && meterRegistry != null) {
                        sample.stop(meterRegistry.timer("neul.llm.api.latency"));
                    }
                    return parseOllamaResponse(response, chats, originalIdToLogicalId);
                });
    }

    public Mono<HighlightDecision> analyzeHighlight(HighlightPromptPayload payload) {
        return fetchFewShotExamples(payload)
                .flatMap(fewShot -> doAnalyzeHighlight(payload, fewShot))
                .onErrorResume(error -> {
                    log.warn("[Ollama] Highlight analysis failed: {}", error.getMessage());
                    return Mono.just(HighlightDecision.fallback("LLM highlight analysis failed, fallback to heuristic ranking."));
                });
    }

    private Mono<String> fetchFewShotExamples(HighlightPromptPayload payload) {
        Map<String, Object> body = Map.ofEntries(
                Map.entry("videoNo", payload.videoNo()),
                Map.entry("category", payload.videoCategory() != null ? payload.videoCategory() : ""),
                Map.entry("sceneLabel", ""),
                Map.entry("emotionDominance", payload.emotionDominance() != null ? payload.emotionDominance() : ""),
                Map.entry("densityRatio", payload.densityRatio()),
                Map.entry("uniqueUserRatio", payload.uniqueUserRatio()),
                Map.entry("hypeRatio", payload.hypeRatio()),
                Map.entry("laughRatio", payload.laughRatio()),
                Map.entry("surpriseRatio", payload.surpriseRatio()),
                Map.entry("tensionRatio", payload.tensionRatio()),
                Map.entry("keywordSummary", payload.keywordSummary() != null ? payload.keywordSummary() : "")
        );

        return webClient.post()
                .uri(coreApiBaseUrl + "/internal/rag/few-shot?k=3")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn("");
    }

    private Mono<HighlightDecision> doAnalyzeHighlight(HighlightPromptPayload payload, String fewShot) {
        try {
            String userPrompt = promptTemplateService.render(promptProperties.getHighlightUser(), Map.ofEntries(
                    Map.entry("videoTitle", payload.videoTitle()),
                    Map.entry("videoCategory", payload.videoCategory()),
                    Map.entry("durationSeconds", String.valueOf(payload.durationSeconds())),
                    Map.entry("progressRatio", formatDecimal(payload.progressRatio())),
                    Map.entry("startSeconds", String.valueOf(payload.startSeconds())),
                    Map.entry("endSeconds", String.valueOf(payload.endSeconds())),
                    Map.entry("densityRatio", formatDecimal(payload.densityRatio())),
                    Map.entry("zScore", formatDecimal(payload.zScore())),
                    Map.entry("hypeRatio", formatDecimal(payload.hypeRatio())),
                    Map.entry("laughRatio", formatDecimal(payload.laughRatio())),
                    Map.entry("surpriseRatio", formatDecimal(payload.surpriseRatio())),
                    Map.entry("tensionRatio", formatDecimal(payload.tensionRatio())),
                    Map.entry("repeatedRatio", formatDecimal(payload.repeatedRatio())),
                    Map.entry("dominantSenderRatio", formatDecimal(payload.dominantSenderRatio())),
                    Map.entry("goodbyeRatio", formatDecimal(payload.goodbyeRatio())),
                    Map.entry("keywordSummary", payload.keywordSummary()),
                    Map.entry("chatBundle", payload.chatBundle()),
                    Map.entry("fewShotExamples", fewShot)
            ));

            OllamaRequest requestDto = buildOllamaRequest(
                    getHighlightSystemPrompt(),
                    userPrompt,
                    0.2,
                    768
            );

            return webClient.post()
                    .uri(ollamaApiUrl)
                    .bodyValue(requestDto)
                    .retrieve()
                    .bodyToMono(OllamaResponse.class)
                    .timeout(Duration.ofSeconds(45))
                    .map(this::parseHighlightDecision);
        } catch (Exception error) {
            log.warn("[Ollama] Highlight prompt preparation failed: {}", error.getMessage());
            return Mono.just(HighlightDecision.fallback("LLM highlight prompt preparation failed, fallback to heuristic ranking."));
        }
    }

    // ─── 입력 가드레일 ────────────────────────────────────────────────────────

    /**
     * 빈 채팅 제거 → 배치 크기 상한(MAX_BATCH_SIZE) → 총 입력 문자 상한(MAX_INPUT_CHARS) 순서로 적용.
     * LLM 입력 토큰 폭발과 타임아웃을 예방하기 위한 경계 조건 강제.
     */
    private List<CompressedChat> applyInputGuardrails(List<CompressedChat> chats) {
        List<CompressedChat> filtered = chats.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .collect(Collectors.toList());

        List<CompressedChat> sized = filtered.size() > MAX_BATCH_SIZE
                ? filtered.subList(0, MAX_BATCH_SIZE)
                : filtered;

        List<CompressedChat> result = new ArrayList<>();
        int totalChars = 0;
        for (CompressedChat chat : sized) {
            int len = chat.getContent().length();
            if (totalChars + len > MAX_INPUT_CHARS) break;
            result.add(chat);
            totalChars += len;
        }

        if (result.size() < chats.size()) {
            log.info("[Ollama] Input guardrail applied: {} -> {} chats (chars={})",
                    chats.size(), result.size(), totalChars);
            recordCount("neul.llm.batch.capped");
        }
        return result;
    }

    /**
     * 배치 크기에 비례한 동적 타임아웃. 고정 60초 대신 실제 부하에 맞게 조정.
     * 기본 20초 + 채팅 1개당 1.5초, 최대 90초.
     */
    private Duration computeTimeout(int batchSize) {
        long seconds = Math.min(90L, 20L + (long) (batchSize * 1.5));
        return Duration.ofSeconds(seconds);
    }

    // ─── 출력 가드레일 ────────────────────────────────────────────────────────

    /**
     * LLM 응답의 감정 점수를 검증하고 정규화.
     * - 7개 감정 키 완결성 보장 (누락 키는 0.0으로 채움)
     * - 각 점수를 0.0~1.0 범위로 클램핑
     * - 합계가 0이면 NEUTRAL로 교정 (의미 없는 결과 방지)
     * - 예상 외 키 포함 시 경고 로그
     */
    private Map<String, Double> validateScores(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return createNeutralScores();
        }

        Map<String, Double> validated = new HashMap<>();
        for (String emotion : VALID_EMOTIONS) {
            double score = raw.getOrDefault(emotion, 0.0);
            validated.put(emotion, Math.max(0.0, Math.min(1.0, score)));
        }

        double total = validated.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total < 0.001) {
            log.warn("[Ollama] All emotion scores are zero, falling back to NEUTRAL.");
            recordCount("neul.llm.output.zeroed");
            return createNeutralScores();
        }

        raw.keySet().stream()
                .filter(k -> !VALID_EMOTIONS.contains(k))
                .findFirst()
                .ifPresent(k -> log.warn("[Ollama] Unexpected emotion key in LLM output: {}", k));

        return validated;
    }

    // ─── 내부 유틸 ───────────────────────────────────────────────────────────

    private void recordCount(String name) {
        if (meterRegistry != null) meterRegistry.counter(name).increment();
    }

    private String getSentimentSystemPrompt() {
        return promptTemplateService.render(promptProperties.getSentimentSystem(), Map.of());
    }

    private String getHighlightSystemPrompt() {
        return promptTemplateService.render(promptProperties.getHighlightSystem(), Map.of());
    }

    private String buildSentimentPrompt(List<CompressedChat> chats, Map<String, String> idMap) {
        StringBuilder sb = new StringBuilder();

        for (CompressedChat chat : chats) {
            String logicalId = idMap.get(chat.getRepresentativeId());
            sb.append(String.format("[%s] %s (%d occurrences)\n",
                    logicalId, chat.getContent(), chat.getCount()));
        }
        return promptTemplateService.render(promptProperties.getSentimentUser(), Map.of(
                "messages", sb.toString().trim()
        ));
    }

    private List<AnalyzedChatMessage> parseOllamaResponse(OllamaResponse response, List<CompressedChat> originalChats, Map<String, String> originalIdToLogicalId) {
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
                String logicalId = originalIdToLogicalId.getOrDefault(chat.getRepresentativeId(), "-1");
                Map<String, Double> rawScores = emotionMapByLogicalId.get(logicalId);

                // [가드레일 출력] 점수 범위 및 감정 키 완결성 검증
                Map<String, Double> scores = validateScores(rawScores);

                return AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .senderId(chat.getRepresentativeSenderId())
                        .emotionScores(scores)
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
        return createFallbackMessages(chats);
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

    public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(List<CompressedChat> chats, Throwable t) {
        log.error("[Ollama] API call failed. CircuitBreaker fallback triggered. Cause: {}", t.getMessage());
        return Mono.just(createFallbackMessages(chats));
    }

    private OllamaRequest buildOllamaRequest(String systemPrompt, String userPrompt, double temperature, int numPredict) {
        return OllamaRequest.builder()
                .model(ollamaModel)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(systemPrompt).build(),
                        OllamaMessage.builder().role("user").content(userPrompt).build()
                ))
                .stream(false)
                .format("json")
                .options(Map.of(
                        "temperature", temperature,
                        "num_predict", numPredict,
                        "top_p", 0.8
                ))
                .build();
    }

    private List<AnalyzedChatMessage> createFallbackMessages(List<CompressedChat> chats) {
        return chats.stream()
                .map(chat -> AnalyzedChatMessage.builder()
                        .messageId(chat.getRepresentativeId())
                        .roomId(chat.getRoomId())
                        .content(chat.getContent())
                        .senderId(chat.getRepresentativeSenderId())
                        .emotionScores(createNeutralScores())
                        .timestamp(chat.getTimestamp())
                        .analyzedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
    }
}
