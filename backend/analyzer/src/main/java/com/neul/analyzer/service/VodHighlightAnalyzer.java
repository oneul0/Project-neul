package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodCrawlCompletedEvent;
import com.neul.common.dto.VodAnalysisCompletedEvent;
import com.neul.common.dto.VodHighlightPoint;
import com.neul.common.dto.VodTimelinePoint;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Service
public class VodHighlightAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VodHighlightAnalyzer.class);

    private static final int WINDOW_SECONDS = 30;
    private static final int MIN_HIGHLIGHTS = 5;
    private static final int MAX_HIGHLIGHTS = 24;
    private static final int EDGE_WINDOW_SECONDS = 300;
    private static final int LLM_REVIEW_LIMIT = 12;
    private static final int LLM_REVIEW_CONCURRENCY = 3;
    private static final Duration LLM_REVIEW_TIMEOUT = Duration.ofMinutes(4);

    private static final List<String> LAUGH_TOKENS = List.of("\u314b\u314b", "\u314e\u314e", "lol", "lmao", "rofl");
    private static final List<String> SURPRISE_TOKENS = List.of("\uc640", "\ud5c9", "\ub300\ubc15", "omg", "wtf");
    private static final List<String> HYPE_TOKENS = List.of("\ub808\uc804\ub4dc", "goat", "\uc9c0\ub9b0", "\ubbf8\uccd0", "\uc18c\ub984");
    private static final List<String> TENSION_TOKENS = List.of("\uc5b5\uae4c", "\uc2f8\uc6c0", "\ubd88\uc548", "\uc9d1\uc911", "\ubd84\ub178");
    private static final List<String> GOODBYE_TOKENS = List.of("방종", "수고", "ㅂㅇ", "ㅃㅇ", "빠이", "바이", "goodbye", "bye", "수고했", "고생했");
    private static final Set<String> STOPWORDS = Set.of(
            "진짜", "그냥", "이번", "이거", "저거", "근데", "이제", "오늘", "지금", "아까", "뭔가", "약간",
            "ㅋㅋ", "ㅋㅋㅋ", "ㅎㅎ", "ㅎㅎㅎ", "ㄹㅇ", "ㅇㅈ", "와", "헉", "대박", "미쳤다", "실화냐"
    );
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^0-9a-zA-Zㄱ-ㅎㅏ-ㅣ가-힣]+");

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OllamaAnalyzerService ollamaAnalyzerService;
    private final Map<String, VideoAggregate> aggregates = new ConcurrentHashMap<>();

    public VodHighlightAnalyzer(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            OllamaAnalyzerService ollamaAnalyzerService
    ) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.ollamaAnalyzerService = ollamaAnalyzerService;
    }

    @KafkaListener(
            topics = "vod-raw-chat-topic",
            groupId = "neul-analyzer-vod-group",
            containerFactory = "vodKafkaListenerContainerFactory"
    )
    public void consumeVodChunks(String json, @Header(KafkaHeaders.RECEIVED_KEY) String videoNo) {
        try {
            JsonNode chats = objectMapper.readTree(json);
            if (!chats.isArray()) {
                return;
            }

            VideoAggregate aggregate = aggregates.computeIfAbsent(videoNo, ignored -> new VideoAggregate());
            synchronized (aggregate) {
                for (JsonNode chat : chats) {
                    aggregate.addChat(chat);
                }
            }
        } catch (Exception e) {
            log.error("[Vod-Analyzer] Failed to consume VOD chunk for videoNo={}", videoNo, e);
        }
    }

    @KafkaListener(topics = "vod-crawl-complete-topic", groupId = "neul-analyzer-vod-complete-group")
    public void consumeCompletion(String json, @Header(KafkaHeaders.RECEIVED_KEY) String videoNo) {
        try {
            VodCrawlCompletedEvent event = objectMapper.readValue(json, VodCrawlCompletedEvent.class);
            VideoAggregate aggregate = aggregates.remove(event.getVideoNo());
            if (aggregate == null || aggregate.windows().isEmpty()) {
                log.warn("[Vod-Analyzer] No aggregated VOD chats for videoNo={}", event.getVideoNo());
                return;
            }

            List<WindowStats> windows;
            List<WindowScore> highlights;
            synchronized (aggregate) {
                windows = new ArrayList<>(aggregate.windows().values());
                highlights = rankWindows(event.getVideoNo(), windows);
            }

            publishTimeline(event.getVideoNo(), windows);
            publishHighlights(event.getVideoNo(), highlights);
            publishCompletion(event.getVideoNo(), windows.size(), highlights.size());

            log.info(
                    "[Vod-Analyzer] Finalized videoNo={}, pages={}, chats={}, windows={}, highlights={}",
                    event.getVideoNo(),
                    event.getPagesProcessed(),
                    event.getChatsCollected(),
                    windows.size(),
                    highlights.size()
            );
            if (!highlights.isEmpty()) {
                WindowScore top = highlights.stream()
                        .max(Comparator.comparingDouble(WindowScore::score))
                        .orElse(highlights.get(0));
                int firstStart = windows.stream().mapToInt(WindowStats::startSeconds).min().orElse(0);
                int lastStart = windows.stream().mapToInt(WindowStats::startSeconds).max().orElse(0);
                log.info(
                        "[Vod-Analyzer] Timeline range videoNo={}, first={}s, last={}s, topScore={}",
                        event.getVideoNo(),
                        firstStart,
                        lastStart,
                        top.score()
                );
            }
        } catch (Exception e) {
            log.error("[Vod-Analyzer] Failed to finalize VOD highlights for videoNo={}", videoNo, e);
        }
    }

    private void publishTimeline(String videoNo, List<WindowStats> windows) throws Exception {
        for (WindowStats window : windows.stream().sorted(Comparator.comparingInt(WindowStats::startSeconds)).toList()) {
            VodTimelinePoint point = VodTimelinePoint.builder()
                    .videoNo(videoNo)
                    .startSeconds(window.startSeconds())
                    .endSeconds(window.startSeconds() + WINDOW_SECONDS)
                    .messageCount(window.messageCount())
                    .participantCount(window.uniqueUsers())
                    .activityScore(window.activityScore())
                    .category(determineCategory(window))
                    .topMessage(window.representativeMessage())
                    .build();

            kafkaTemplate.send("vod-window-summary-topic", videoNo, objectMapper.writeValueAsString(point));
        }
    }

    private void publishHighlights(String videoNo, List<WindowScore> highlights) throws Exception {
        for (WindowScore ranked : highlights) {
            VodHighlightPoint point = VodHighlightPoint.builder()
                    .videoNo(videoNo)
                    .startSeconds(ranked.startSeconds())
                    .endSeconds(ranked.endSeconds())
                    .highlightScore(ranked.score())
                    .intensityScore(ranked.intensityScore())
                    .transitionScore(ranked.transitionScore())
                    .editabilityScore(ranked.editabilityScore())
                    .category(ranked.category())
                    .reactionLabel(ranked.reactionLabel())
                    .description(ranked.description())
                    .reasonSummary(ranked.reasonSummary())
                    .topMessage(ranked.topMessage())
                    .build();

            kafkaTemplate.send("vod-analyzed-topic", videoNo, objectMapper.writeValueAsString(point));
        }
    }

    private void publishCompletion(String videoNo, int timelinePointsCount, int highlightsCount) throws Exception {
        VodAnalysisCompletedEvent event = VodAnalysisCompletedEvent.builder()
                .videoNo(videoNo)
                .timelinePointsCount(timelinePointsCount)
                .highlightsCount(highlightsCount)
                .build();

        kafkaTemplate.send("vod-analysis-complete-topic", videoNo, objectMapper.writeValueAsString(event));
    }

    private List<WindowScore> rankWindows(String videoNo, List<WindowStats> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }

        double averageMessages = windows.stream().mapToInt(WindowStats::messageCount).average().orElse(0.0);
        double averageUsers = windows.stream().mapToInt(WindowStats::uniqueUsers).average().orElse(0.0);
        double averageBursts = windows.stream().mapToDouble(WindowStats::burstSignal).average().orElse(0.0);
        double messageStdDev = standardDeviation(windows.stream().map(WindowStats::messageCount).mapToDouble(Integer::doubleValue).toArray(), averageMessages);
        double userStdDev = standardDeviation(windows.stream().map(WindowStats::uniqueUsers).mapToDouble(Integer::doubleValue).toArray(), averageUsers);
        double burstStdDev = standardDeviation(windows.stream().mapToDouble(WindowStats::burstSignal).toArray(), averageBursts);
        int maxMessages = windows.stream().mapToInt(WindowStats::messageCount).max().orElse(1);
        int maxUsers = windows.stream().mapToInt(WindowStats::uniqueUsers).max().orElse(1);
        double maxBurst = windows.stream().mapToDouble(WindowStats::burstSignal).max().orElse(1.0);
        int firstWindowStart = windows.stream().mapToInt(WindowStats::startSeconds).min().orElse(0);
        int lastWindowStart = windows.stream().mapToInt(WindowStats::startSeconds).max().orElse(0);

        List<WindowScore> scored = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            WindowStats previous = index > 0 ? windows.get(index - 1) : null;
            WindowStats current = windows.get(index);
            WindowStats next = index < windows.size() - 1 ? windows.get(index + 1) : null;
            scored.add(scoreWindow(
                    videoNo,
                    current,
                    previous,
                    next,
                    firstWindowStart,
                    lastWindowStart,
                    averageMessages,
                    averageUsers,
                    averageBursts,
                    messageStdDev,
                    userStdDev,
                    burstStdDev,
                    maxMessages,
                    maxUsers,
                    maxBurst
            ));
        }

        scored = scored.stream()
                .sorted(Comparator.comparingDouble(WindowScore::score).reversed())
                .toList();

        int targetCount = Math.min(MAX_HIGHLIGHTS, Math.max(MIN_HIGHLIGHTS, (int) Math.ceil(windows.size() * 0.12)));
        Map<Integer, WindowStats> windowByStart = windows.stream()
                .collect(LinkedHashMap::new, (map, window) -> map.put(window.startSeconds(), window), LinkedHashMap::putAll);
        scored = enrichWithLlmReview(videoNo, scored, windowByStart, targetCount);
        List<WindowScore> selected = selectDistributedHighlights(scored, targetCount);
        List<WindowScore> fallbackCandidates = scored.stream()
                .filter(candidate -> !candidate.hardRejected())
                .toList();

        if (selected.size() < Math.min(MIN_HIGHLIGHTS, fallbackCandidates.size())) {
            selected = mergeUniqueByStartSeconds(selected, fallbackCandidates.subList(0, Math.min(MIN_HIGHLIGHTS, fallbackCandidates.size())));
        }

        return selected.stream()
                .sorted(Comparator.comparingInt(WindowScore::startSeconds))
                .toList();
    }

    private List<WindowScore> selectDistributedHighlights(List<WindowScore> scored, int targetCount) {
        if (scored.isEmpty()) {
            return List.of();
        }

        List<WindowScore> eligible = scored.stream()
                .filter(candidate -> !candidate.hardRejected())
                .toList();

        if (eligible.isEmpty()) {
            return List.of();
        }

        int bucketCount = Math.min(targetCount, Math.min(8, Math.max(4, targetCount / 2)));
        int maxStart = eligible.stream().mapToInt(WindowScore::startSeconds).max().orElse(0);
        int bucketSize = Math.max(1, (maxStart + WINDOW_SECONDS) / bucketCount);
        int globalQuota = Math.max(0, targetCount - bucketCount);

        Map<Integer, WindowScore> selectedByStart = new LinkedHashMap<>();

        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int bucketStart = bucket * bucketSize;
            int bucketEnd = bucket == bucketCount - 1 ? Integer.MAX_VALUE : bucketStart + bucketSize;

            eligible.stream()
                    .filter(candidate -> candidate.startSeconds() >= bucketStart && candidate.startSeconds() < bucketEnd)
                    .findFirst()
                    .ifPresent(candidate -> selectedByStart.putIfAbsent(candidate.startSeconds(), candidate));
        }

        if (globalQuota > 0) {
            for (WindowScore candidate : eligible) {
                if (selectedByStart.size() >= targetCount) {
                    break;
                }
                if (selectedByStart.containsKey(candidate.startSeconds())) {
                    continue;
                }
                selectedByStart.put(candidate.startSeconds(), candidate);
            }
        }

        if (selectedByStart.size() < targetCount) {
            for (WindowScore candidate : eligible) {
                if (selectedByStart.size() >= targetCount) {
                    break;
                }
                selectedByStart.putIfAbsent(candidate.startSeconds(), candidate);
            }
        }

        return new ArrayList<>(selectedByStart.values());
    }

    private List<WindowScore> mergeUniqueByStartSeconds(List<WindowScore> first, List<WindowScore> second) {
        Map<Integer, WindowScore> merged = new LinkedHashMap<>();
        for (WindowScore item : first) {
            merged.putIfAbsent(item.startSeconds(), item);
        }
        for (WindowScore item : second) {
            merged.putIfAbsent(item.startSeconds(), item);
        }
        return new ArrayList<>(merged.values());
    }

    private WindowScore scoreWindow(
            String videoNo,
            WindowStats window,
            WindowStats previous,
            WindowStats next,
            int firstWindowStart,
            int lastWindowStart,
            double averageMessages,
            double averageUsers,
            double averageBursts,
            double messageStdDev,
            double userStdDev,
            double burstStdDev,
            int maxMessages,
            int maxUsers,
            double maxBurst
    ) {
        double densityFactor = averageMessages == 0 ? 1.0 : ((double) window.messageCount() / averageMessages);
        double userFactor = averageUsers == 0 ? 1.0 : ((double) window.uniqueUsers() / averageUsers);
        double burstFactor = averageBursts == 0 ? 1.0 : (window.burstSignal() / averageBursts);
        double messageZScore = zScore(window.messageCount(), averageMessages, messageStdDev);
        double userZScore = zScore(window.uniqueUsers(), averageUsers, userStdDev);
        double burstZScore = zScore(window.burstSignal(), averageBursts, burstStdDev);

        double densityScore = Math.min(14.0, densityFactor * 4.4 + ((double) window.messageCount() / Math.max(1, maxMessages)) * 5.0);
        double userScore = Math.min(9.0, userFactor * 3.4 + ((double) window.uniqueUsers() / Math.max(1, maxUsers)) * 3.0);
        double burstScore = Math.min(8.0, burstFactor * 3.4 + (window.burstSignal() / Math.max(1.0, maxBurst)) * 2.6);
        double zScoreBoost = Math.max(0.0, messageZScore * 1.8) + Math.max(0.0, userZScore * 0.7) + Math.max(0.0, burstZScore * 0.9);
        double laughScore = Math.min(5.0, window.laughCount() * 0.9);
        double surpriseScore = Math.min(5.0, window.surpriseCount() * 1.0);
        double hypeScore = Math.min(5.0, window.hypeCount() * 1.0);
        double tensionScore = Math.min(4.0, window.tensionCount() * 0.8);
        double repetitionScore = Math.min(4.0, window.repeatedMessageCount() * 0.6);
        double punctuationScore = Math.min(3.0, window.punctuationBurstCount() * 0.5);
        double messageVarietyScore = Math.min(4.0, window.messageVariety() * 2.0);
        double balanceScore = Math.min(3.0, window.userCoverageRatio() * 3.0);
        double transitionScore = calculateTransitionScore(window, previous, next, averageMessages, averageUsers);
        String category = determineCategory(window);
        String reactionLabel = categoryLabel(category);
        boolean hardRejected = shouldHardReject(window);
        double negativePenalty = calculateNegativePenalty(window);
        double edgePenalty = calculateEdgePenalty(window.startSeconds(), firstWindowStart, lastWindowStart);

        double intensityScore = densityScore
                + userScore
                + burstScore
                + zScoreBoost
                + laughScore
                + surpriseScore
                + hypeScore
                + tensionScore
                + repetitionScore
                + punctuationScore;

        double editabilityScore = Math.min(
                20.0,
                (messageVarietyScore * 2.2)
                        + (balanceScore * 1.8)
                        + Math.min(4.0, window.representativeMessage().isBlank() ? 0.0 : 4.0)
                        + Math.min(5.0, transitionScore * 0.65)
        );

        double totalScore = ((intensityScore * 0.55) + (transitionScore * 0.20) + (editabilityScore * 0.25))
                * edgePenalty
                * negativePenalty;
        if (hardRejected) {
            totalScore *= 0.18;
        }

        String reasonSummary = buildReasonSummary(window, reactionLabel, intensityScore, transitionScore, editabilityScore);
        String description = String.format(
                "%s 반응이 몰린 구간이에요. 채팅 %d개와 참여자 %d명이 함께 반응했어요.",
                reactionLabel,
                window.messageCount(),
                window.uniqueUsers()
        );

        return new WindowScore(
                videoNo,
                window.startSeconds(),
                window.startSeconds() + WINDOW_SECONDS,
                totalScore,
                intensityScore,
                transitionScore,
                editabilityScore,
                category,
                reactionLabel,
                description,
                reasonSummary,
                window.representativeMessage(),
                messageZScore,
                burstZScore,
                hardRejected
        );
    }

    private List<WindowScore> enrichWithLlmReview(String videoNo, List<WindowScore> scored, Map<Integer, WindowStats> windowByStart, int targetCount) {
        if (scored.isEmpty()) {
            return scored;
        }

        List<WindowScore> reviewCandidates = scored.stream()
                .filter(candidate -> !candidate.hardRejected())
                .limit(Math.min(LLM_REVIEW_LIMIT, Math.max(targetCount * 2, MIN_HIGHLIGHTS)))
                .toList();

        if (reviewCandidates.isEmpty()) {
            return scored;
        }

        Map<Integer, HighlightDecision> reviewed;
        try {
            reviewed = Flux.fromIterable(reviewCandidates)
                    .flatMap(candidate -> {
                        WindowStats window = windowByStart.get(candidate.startSeconds());
                        if (window == null) {
                            return Flux.empty();
                        }
                        return ollamaAnalyzerService.analyzeHighlight(buildHighlightPayload(videoNo, candidate, window))
                                .map(decision -> Map.entry(candidate.startSeconds(), decision))
                                .flux();
                    }, LLM_REVIEW_CONCURRENCY)
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .block(LLM_REVIEW_TIMEOUT);
        } catch (IllegalStateException error) {
            Throwable cause = error.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("[Vod-Analyzer] Timed out during LLM highlight review for videoNo={}, fallback to heuristic ranking.", videoNo);
                return scored;
            }
            throw error;
        }

        if (reviewed == null || reviewed.isEmpty()) {
            return scored;
        }

        return scored.stream()
                .map(score -> applyHighlightDecision(score, reviewed.get(score.startSeconds())))
                .sorted(Comparator.comparingDouble(WindowScore::score).reversed())
                .toList();
    }

    private HighlightPromptPayload buildHighlightPayload(String videoNo, WindowScore score, WindowStats window) {
        String keywordSummary = window.topKeywords(5).entrySet().stream()
                .map(entry -> String.format("- '%s' (%d회)", entry.getKey(), entry.getValue()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 뚜렷한 핵심 키워드 없음");

        String negativeSignals = String.join("\n", List.of(
                String.format("- 동일 메시지 반복 비율: %.2f", window.repeatedRatio()),
                String.format("- 동일 발화자 집중도: %.2f", window.dominantSenderRatio()),
                String.format("- 방종/인사 키워드 비율: %.2f", window.goodbyeRatio()),
                String.format("- 대표 채팅: %s", window.representativeMessage().isBlank() ? "없음" : window.representativeMessage())
        ));

        String chatBundle = window.topMessages(8).entrySet().stream()
                .map(entry -> String.format("- %s (x%d)", entry.getKey(), entry.getValue()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 채팅 샘플 없음");

        return new HighlightPromptPayload(
                videoNo,
                score.startSeconds(),
                score.endSeconds(),
                window.messageCount(),
                window.uniqueUsers(),
                score.messageZScore() <= 0 ? 1.0 : 1.0 + score.messageZScore(),
                score.messageZScore(),
                score.burstZScore(),
                window.repeatedRatio(),
                window.dominantSenderRatio(),
                window.goodbyeRatio(),
                keywordSummary,
                negativeSignals,
                chatBundle
        );
    }

    private WindowScore applyHighlightDecision(WindowScore score, HighlightDecision decision) {
        if (decision == null) {
            return score;
        }

        String decisionReasoning = (decision.reasoning() == null || decision.reasoning().isBlank())
                ? "LLM reasoning not provided."
                : decision.reasoning().trim();

        if (!decision.isHighlight()) {
            return score.withDecision(
                    score.score() * 0.38,
                    score.category(),
                    score.reactionLabel(),
                    score.description(),
                    decisionReasoning + " | " + score.reasonSummary(),
                    true
            );
        }

        String normalizedCategory = normalizeEditorialCategory(decision.category());
        String summary = (decision.summary() == null || decision.summary().isBlank())
                ? "하이라이트 후보 구간입니다."
                : decision.summary().trim();
        double intensityBoost = 1.0 + ((Math.max(1, Math.min(10, decision.intensity())) - 5) * 0.05);
        return score.withDecision(
                (score.score() + 2.4) * intensityBoost,
                normalizedCategory,
                normalizedCategory,
                summary,
                decisionReasoning + " | " + score.reasonSummary(),
                false
        );
    }

    private String normalizeEditorialCategory(String category) {
        if (category == null || category.isBlank()) {
            return "소통";
        }
        return switch (category.trim()) {
            case "슈퍼플레이", "대참사", "운", "소통" -> category.trim();
            default -> "소통";
        };
    }

    private double calculateEdgePenalty(int startSeconds, int firstWindowStart, int lastWindowStart) {
        if (startSeconds - firstWindowStart < EDGE_WINDOW_SECONDS / 2 || lastWindowStart - startSeconds < EDGE_WINDOW_SECONDS / 2) {
            return 0.58;
        }
        if (startSeconds - firstWindowStart < EDGE_WINDOW_SECONDS || lastWindowStart - startSeconds < EDGE_WINDOW_SECONDS) {
            return 0.74;
        }
        return 1.0;
    }

    private boolean shouldHardReject(WindowStats window) {
        return window.goodbyeRatio() >= 0.18 || (window.goodbyeKeywordCount() >= 3 && window.messageCount() <= 12);
    }

    private double calculateNegativePenalty(WindowStats window) {
        double penalty = 1.0;
        if (window.repeatedRatio() >= 0.45) {
            penalty *= 0.72;
        }
        if (window.dominantSenderRatio() >= 0.45) {
            penalty *= 0.78;
        }
        if (window.goodbyeRatio() >= 0.10) {
            penalty *= 0.65;
        }
        return penalty;
    }

    private double zScore(double value, double mean, double standardDeviation) {
        if (standardDeviation <= 0.0001) {
            return 0.0;
        }
        return (value - mean) / standardDeviation;
    }

    private double standardDeviation(double[] values, double mean) {
        if (values.length == 0) {
            return 0.0;
        }
        double variance = Arrays.stream(values)
                .map(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    private String buildReasonSummary(
            WindowStats window,
            String reactionLabel,
            double intensityScore,
            double transitionScore,
            double editabilityScore
    ) {
        List<String> reasons = new ArrayList<>();

        reasons.add(String.format("이 구간에서 채팅 %d개, 참여자 %d명이 반응했어요.", window.messageCount(), window.uniqueUsers()));

        if (window.burstSignal() >= 3.0) {
            reasons.add("감탄이나 반복 반응이 눈에 띄게 몰렸어요.");
        }
        if (window.repeatedMessageCount() >= 2) {
            reasons.add("비슷한 메시지가 여러 번 반복돼서 장면 반응이 또렷했어요.");
        }
        if (transitionScore >= 4.5) {
            reasons.add("직전 구간보다 분위기가 확 바뀌는 편집 포인트예요.");
        }
        if (editabilityScore >= 8.0) {
            reasons.add("짧게 잘라 하이라이트로 쓰기 좋은 흐름이에요.");
        }

        if (intensityScore >= 12.0) {
            reasons.add("반응 강도가 높아서 먼저 확인해볼 만해요.");
        } else if (transitionScore >= 3.5) {
            reasons.add("큰 폭발 구간은 아니어도 편집 포인트로 보기 좋아요.");
        } else {
            reasons.add("조용한 흐름 속에서도 상대적으로 반응이 살아난 구간이에요.");
        }

        return String.join(" | ", reasons);
    }

    private double calculateTransitionScore(
            WindowStats current,
            WindowStats previous,
            WindowStats next,
            double averageMessages,
            double averageUsers
    ) {
        if (previous == null) {
            return 0.0;
        }

        double previousMessages = Math.max(previous.messageCount(), 1);
        double previousUsers = Math.max(previous.uniqueUsers(), 1);
        double messageJump = current.messageCount() / previousMessages;
        double userJump = current.uniqueUsers() / previousUsers;

        double quietBaseline = previous.messageCount() < Math.max(averageMessages * 0.65, 4.0) ? 1.0 : 0.0;
        double burstFromQuiet = quietBaseline * Math.max(0.0, messageJump - 1.0) * 2.2;
        double userSurge = quietBaseline * Math.max(0.0, userJump - 1.0) * 1.6;

        double sustainedBonus = 0.0;
        if (next != null) {
            double nextMessages = Math.max(next.messageCount(), 1);
            double nextUsers = Math.max(next.uniqueUsers(), 1);
            if (current.messageCount() >= averageMessages && nextMessages >= averageMessages * 0.8) {
                sustainedBonus += 1.5;
            }
            if (current.uniqueUsers() >= averageUsers && nextUsers >= averageUsers * 0.8) {
                sustainedBonus += 1.0;
            }
        }

        return Math.min(7.0, burstFromQuiet + userSurge + sustainedBonus);
    }

    private String determineCategory(WindowStats window) {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("LAUGH", window.laughCount());
        categories.put("WONDER", window.surpriseCount());
        categories.put("HYPE", window.hypeCount());
        categories.put("TENSION", window.tensionCount());

        return categories.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse("HOT_MOMENT");
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "LAUGH" -> "\uc6c3\uc74c";
            case "WONDER" -> "\ub180\ub78c";
            case "HYPE" -> "\uace0\uc870";
            case "TENSION" -> "\uae34\uc7a5";
            default -> "\ubc18\uc751";
        };
    }

    private static int countMatches(String text, List<String> tokens) {
        int count = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private static String normalizeMessage(String message) {
        return message.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[!?.~]+", "");
    }

    private static int extractVideoSeconds(JsonNode chat) {
        JsonNode videoInSeconds = chat.path("videoInSeconds");
        if (videoInSeconds.isNumber()) {
            return Math.max(videoInSeconds.asInt(), 0);
        }

        JsonNode playerMessageTime = chat.path("playerMessageTime");
        if (playerMessageTime.isNumber()) {
            return (int) Math.max(playerMessageTime.asLong() / 1000L, 0);
        }

        JsonNode messageTime = chat.path("messageTime");
        if (messageTime.isNumber()) {
            return (int) Math.max(messageTime.asLong() / 1000L, 0);
        }

        return 0;
    }

    private static String extractMessage(JsonNode chat) {
        String message = chat.path("message").asText("").trim();
        if (!message.isBlank()) {
            return message;
        }

        String msg = chat.path("msg").asText("").trim();
        if (!msg.isBlank()) {
            return msg;
        }

        String content = chat.path("content").asText("").trim();
        if (!content.isBlank()) {
            return content;
        }

        return "";
    }

    private static final class VideoAggregate {
        private final Map<Integer, WindowStats> windows = new TreeMap<>();

        void addChat(JsonNode chat) {
            int seconds = extractVideoSeconds(chat);
            int windowKey = (seconds / WINDOW_SECONDS) * WINDOW_SECONDS;
            WindowStats window = windows.computeIfAbsent(windowKey, WindowStats::new);
            window.record(chat);
        }

        Map<Integer, WindowStats> windows() {
            return windows;
        }
    }

    private static final class WindowStats {
        private final int startSeconds;
        private int messageCount;
        private int uniqueUsers;
        private int laughCount;
        private int surpriseCount;
        private int hypeCount;
        private int tensionCount;
        private int punctuationBurstCount;
        private int repeatedMessageCount;
        private int goodbyeKeywordCount;
        private final Set<String> seenUsers = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> normalizedCounts = new HashMap<>();
        private final Map<String, String> originalMessages = new HashMap<>();
        private final Map<String, Integer> keywordCounts = new HashMap<>();
        private final Map<String, Integer> senderCounts = new HashMap<>();
        private final Map<String, Integer> senderMessageCounts = new HashMap<>();
        private String latestMessage = "";

        private WindowStats(int startSeconds) {
            this.startSeconds = startSeconds;
        }

        private void record(JsonNode chat) {
            messageCount++;

            String senderId = chat.path("userIdHash").asText(chat.path("senderId").asText(""));
            if (!senderId.isBlank() && seenUsers.add(senderId)) {
                uniqueUsers++;
            }
            if (!senderId.isBlank()) {
                senderCounts.merge(senderId, 1, Integer::sum);
            }

            String message = extractMessage(chat);
            String lower = message.toLowerCase(Locale.ROOT);
            String normalized = normalizeMessage(message);
            if (!normalized.isBlank()) {
                int next = normalizedCounts.merge(normalized, 1, Integer::sum);
                if (next >= 3) {
                    repeatedMessageCount++;
                }
                originalMessages.putIfAbsent(normalized, message);
                latestMessage = message;
                if (!senderId.isBlank()) {
                    int senderDuplicateCount = senderMessageCounts.merge(senderId + "::" + normalized, 1, Integer::sum);
                    if (senderDuplicateCount >= 2) {
                        repeatedMessageCount++;
                    }
                }
            }

            if (containsGoodbyeKeyword(lower)) {
                goodbyeKeywordCount++;
            }

            extractKeywords(lower).forEach(token -> keywordCounts.merge(token, 1, Integer::sum));

            laughCount += countMatches(lower, LAUGH_TOKENS);
            surpriseCount += countMatches(lower, SURPRISE_TOKENS);
            hypeCount += countMatches(lower, HYPE_TOKENS);
            tensionCount += countMatches(lower, TENSION_TOKENS);

            if (message.contains("!!") || message.contains("??") || message.contains("!?")) {
                punctuationBurstCount++;
                surpriseCount++;
            }
        }

        private String representativeMessage() {
            String representative = normalizedCounts.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparing(entry -> originalMessages.getOrDefault(entry.getKey(), "").length()))
                    .map(entry -> originalMessages.get(entry.getKey()))
                    .orElse("");

            if (!representative.isBlank()) {
                return representative;
            }
            return latestMessage;
        }

        private double burstSignal() {
            return laughCount + surpriseCount + hypeCount + tensionCount + punctuationBurstCount + (repeatedMessageCount * 0.5);
        }

        private double messageVariety() {
            if (messageCount == 0) {
                return 0;
            }
            return (double) normalizedCounts.size() / messageCount;
        }

        private double userCoverageRatio() {
            if (messageCount == 0) {
                return 0;
            }
            return (double) uniqueUsers / messageCount;
        }

        private double activityScore() {
            return (messageCount * 1.2)
                    + (uniqueUsers * 2.1)
                    + burstSignal()
                    + (messageVariety() * 8.0)
                    + (userCoverageRatio() * 6.0);
        }

        private Map<String, Integer> topMessages(int limit) {
            return normalizedCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(LinkedHashMap::new,
                            (map, entry) -> map.put(originalMessages.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()),
                            LinkedHashMap::putAll);
        }

        private Map<String, Integer> topKeywords(int limit) {
            return keywordCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(LinkedHashMap::new,
                            (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                            LinkedHashMap::putAll);
        }

        private double repeatedRatio() {
            if (messageCount == 0) {
                return 0.0;
            }
            return (double) repeatedMessageCount / messageCount;
        }

        private double dominantSenderRatio() {
            if (messageCount == 0 || senderCounts.isEmpty()) {
                return 0.0;
            }
            int maxSenderMessages = senderCounts.values().stream().max(Integer::compareTo).orElse(0);
            return (double) maxSenderMessages / messageCount;
        }

        private double goodbyeRatio() {
            if (messageCount == 0) {
                return 0.0;
            }
            return (double) goodbyeKeywordCount / messageCount;
        }

        private int goodbyeKeywordCount() {
            return goodbyeKeywordCount;
        }

        private int startSeconds() {
            return startSeconds;
        }

        private int messageCount() {
            return messageCount;
        }

        private int uniqueUsers() {
            return uniqueUsers;
        }

        private int laughCount() {
            return laughCount;
        }

        private int surpriseCount() {
            return surpriseCount;
        }

        private int hypeCount() {
            return hypeCount;
        }

        private int tensionCount() {
            return tensionCount;
        }

        private int punctuationBurstCount() {
            return punctuationBurstCount;
        }

        private int repeatedMessageCount() {
            return repeatedMessageCount;
        }
    }

    private record WindowScore(
            String videoNo,
            int startSeconds,
            int endSeconds,
            double score,
            double intensityScore,
            double transitionScore,
            double editabilityScore,
            String category,
            String reactionLabel,
            String description,
            String reasonSummary,
            String topMessage,
            double messageZScore,
            double burstZScore,
            boolean hardRejected
    ) {

        private WindowScore withDecision(
                double updatedScore,
                String updatedCategory,
                String updatedReactionLabel,
                String updatedDescription,
                String updatedReasonSummary,
                boolean rejectedByLlm
        ) {
            return new WindowScore(
                    videoNo,
                    startSeconds,
                    endSeconds,
                    updatedScore,
                    intensityScore,
                    transitionScore,
                    editabilityScore,
                    updatedCategory,
                    updatedReactionLabel,
                    updatedDescription,
                    updatedReasonSummary,
                    topMessage,
                    messageZScore,
                    burstZScore,
                    hardRejected || rejectedByLlm
            );
        }
    }

    private static boolean containsGoodbyeKeyword(String lower) {
        return GOODBYE_TOKENS.stream().anyMatch(lower::contains);
    }

    private static List<String> extractKeywords(String lower) {
        if (lower == null || lower.isBlank()) {
            return List.of();
        }
        String[] rawTokens = TOKEN_SPLIT_PATTERN.split(lower);
        List<String> tokens = new ArrayList<>();
        for (String rawToken : rawTokens) {
            String token = rawToken == null ? "" : rawToken.trim();
            if (token.length() < 2 || STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }
}
