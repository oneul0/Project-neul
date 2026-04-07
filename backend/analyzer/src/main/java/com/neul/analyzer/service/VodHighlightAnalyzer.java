package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodCrawlCompletedEvent;
import com.neul.common.dto.VodAnalysisCompletedEvent;
import com.neul.common.dto.VodAnalysisFailedEvent;
import com.neul.common.dto.VodHighlightPoint;
import com.neul.common.dto.VodTimelinePoint;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
    private static final Duration FINALIZE_QUIET_PERIOD = Duration.ofMillis(1200);
    private static final Duration FINALIZE_RETRY_DELAY = Duration.ofMillis(600);
    private static final int MAX_FINALIZE_RETRIES = 12;
    private static final int SPIKE_BUCKET_SECONDS = 5;
    private static final List<String> GACHA_TOKENS = List.of("가챠", "뽑", "뽑기", "단챠", "10연", "연차", "픽업", "전설", "ssr", "레전", "천장", "확률", "득템", "나왔다");
    private static final List<String> FLEX_TOKENS = List.of("비틱", "부럽", "원트", "한방", "개부럽", "쉽게", "미쳤다", "실화", "말이돼", "와");
    private static final List<String> DISASTER_TOKENS = List.of("억까", "실수", "망", "말아", "터졌", "죽었", "박았", "대참사", "실화냐", "왜이래");
    private static final List<String> CLUTCH_TOKENS = List.of("클러치", "역전", "한타", "캐리", "세이브", "슈퍼플레이", "미쳤다", "각", "뒤집", "살렸다");
    private static final List<String> CHAT_TOKENS = List.of("채팅", "ㅋㅋ", "다들", "도배", "반응", "난리", "훈수", "어그로");

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
    private final Map<String, VodCrawlCompletedEvent> pendingCompletions = new ConcurrentHashMap<>();
    private final Map<String, Long> finalizeGenerations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService finalizeScheduler;
    private final Duration finalizeQuietPeriod;
    private final Duration finalizeRetryDelay;
    private final int maxFinalizeRetries;

    @Autowired
    public VodHighlightAnalyzer(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            OllamaAnalyzerService ollamaAnalyzerService
    ) {
        this(
                objectMapper,
                kafkaTemplate,
                ollamaAnalyzerService,
                Executors.newSingleThreadScheduledExecutor(new FinalizeThreadFactory()),
                FINALIZE_QUIET_PERIOD,
                FINALIZE_RETRY_DELAY,
                MAX_FINALIZE_RETRIES
        );
    }

    VodHighlightAnalyzer(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            OllamaAnalyzerService ollamaAnalyzerService,
            ScheduledExecutorService finalizeScheduler,
            Duration finalizeQuietPeriod,
            Duration finalizeRetryDelay,
            int maxFinalizeRetries
    ) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.ollamaAnalyzerService = ollamaAnalyzerService;
        this.finalizeScheduler = finalizeScheduler;
        this.finalizeQuietPeriod = finalizeQuietPeriod;
        this.finalizeRetryDelay = finalizeRetryDelay;
        this.maxFinalizeRetries = maxFinalizeRetries;
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
            if (pendingCompletions.containsKey(videoNo)) {
                scheduleFinalize(videoNo);
            }
        } catch (Exception e) {
            log.error("[Vod-Analyzer] Failed to consume VOD chunk for videoNo={}", videoNo, e);
        }
    }

    @KafkaListener(topics = "vod-crawl-complete-topic", groupId = "neul-analyzer-vod-complete-group")
    public void consumeCompletion(String json, @Header(KafkaHeaders.RECEIVED_KEY) String videoNo) {
        try {
            VodCrawlCompletedEvent event = objectMapper.readValue(json, VodCrawlCompletedEvent.class);
            pendingCompletions.put(event.getVideoNo(), event);
            scheduleFinalize(event.getVideoNo());
        } catch (Exception e) {
            log.error("[Vod-Analyzer] Failed to finalize VOD highlights for videoNo={}", videoNo, e);
        }
    }

    private void scheduleFinalize(String videoNo) {
        long generation = finalizeGenerations.merge(videoNo, 1L, Long::sum);
        finalizeScheduler.schedule(
                () -> attemptFinalize(videoNo, generation, 0),
                finalizeRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void attemptFinalize(String videoNo, long generation, int retryCount) {
        if (!Objects.equals(finalizeGenerations.get(videoNo), generation)) {
            return;
        }

        VodCrawlCompletedEvent event = pendingCompletions.get(videoNo);
        if (event == null) {
            finalizeGenerations.remove(videoNo);
            return;
        }

        VideoAggregate aggregate = aggregates.get(videoNo);
        if (aggregate == null || aggregate.windows().isEmpty()) {
            if (event.getChatsCollected() <= 0 || retryCount >= maxFinalizeRetries) {
                pendingCompletions.remove(videoNo);
                finalizeGenerations.remove(videoNo);
                try {
                    if (event.getChatsCollected() <= 0) {
                        publishCompletion(videoNo, 0, 0);
                        log.warn("[Vod-Analyzer] Finalized empty VOD analysis for videoNo={} because no chats were collected.", videoNo);
                    } else {
                        publishFailure(event, "채팅 수집은 완료됐지만 분석용 집계가 준비되지 않아 하이라이트 계산을 종료했습니다.");
                    }
                } catch (Exception error) {
                    log.error("[Vod-Analyzer] Failed to publish terminal event for videoNo={}", videoNo, error);
                }
                return;
            }

            finalizeScheduler.schedule(
                    () -> attemptFinalize(videoNo, generation, retryCount + 1),
                    finalizeRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        if (!aggregate.isQuietFor(finalizeQuietPeriod)) {
            finalizeScheduler.schedule(
                    () -> attemptFinalize(videoNo, generation, retryCount + 1),
                    finalizeRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        List<WindowStats> windows;
        synchronized (aggregate) {
            windows = new ArrayList<>(aggregate.windows().values());
        }

        if (windows.isEmpty()) {
            if (retryCount >= maxFinalizeRetries) {
                pendingCompletions.remove(videoNo);
                finalizeGenerations.remove(videoNo);
                try {
                    if (event.getChatsCollected() <= 0) {
                        publishCompletion(videoNo, 0, 0);
                    } else {
                        publishFailure(event, "채팅 집계 결과가 비어 있어 하이라이트 계산을 완료하지 못했습니다.");
                    }
                } catch (Exception error) {
                    log.error("[Vod-Analyzer] Failed to publish terminal event for videoNo={}", videoNo, error);
                }
                return;
            }
            finalizeScheduler.schedule(
                    () -> attemptFinalize(videoNo, generation, retryCount + 1),
                    finalizeRetryDelay.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        if (!pendingCompletions.remove(videoNo, event)) {
            return;
        }
        aggregates.remove(videoNo, aggregate);
        finalizeGenerations.remove(videoNo);

        try {
            List<WindowScore> highlights = rankWindows(event, windows);
            publishTimeline(event.getVideoNo(), windows);
            publishHighlights(event.getVideoNo(), highlights);
            publishCompletion(event.getVideoNo(), windows.size(), highlights.size());

            log.info(
                    "[Vod-Analyzer] Finalized videoNo={}, pages={}, chats={}, windows={}, highlights={}, title={}, category={}",
                    event.getVideoNo(),
                    event.getPagesProcessed(),
                    event.getChatsCollected(),
                    windows.size(),
                    highlights.size(),
                    event.getTitle(),
                    event.getCategory()
            );
        } catch (Exception error) {
            log.error("[Vod-Analyzer] Failed to publish finalized VOD result for videoNo={}", videoNo, error);
            try {
                publishFailure(event, "하이라이트 결과를 저장 가능한 이벤트로 발행하지 못했습니다.");
            } catch (Exception publishError) {
                log.error("[Vod-Analyzer] Failed to publish failure event for videoNo={}", videoNo, publishError);
            }
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
                    .sceneLabel(ranked.sceneLabel())
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

    private void publishFailure(VodCrawlCompletedEvent sourceEvent, String reason) throws Exception {
        VodAnalysisFailedEvent failedEvent = VodAnalysisFailedEvent.builder()
                .videoNo(sourceEvent.getVideoNo())
                .pagesProcessed(sourceEvent.getPagesProcessed())
                .chatsCollected(sourceEvent.getChatsCollected())
                .reason(reason)
                .build();

        kafkaTemplate.send("vod-analysis-failed-topic", sourceEvent.getVideoNo(), objectMapper.writeValueAsString(failedEvent));
    }

    private List<WindowScore> rankWindows(VodCrawlCompletedEvent event, List<WindowStats> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }

        VideoContext videoContext = new VideoContext(
                event.getVideoNo(),
                safeText(event.getTitle(), "제목 정보 없음"),
                safeText(event.getCategory(), "카테고리 정보 없음"),
                event.getDuration() != null ? Math.max(event.getDuration(), WINDOW_SECONDS) : Math.max(windows.stream().mapToInt(WindowStats::startSeconds).max().orElse(0) + WINDOW_SECONDS, WINDOW_SECONDS)
        );

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
                    videoContext.videoNo(),
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
                    videoContext,
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
        scored = enrichWithLlmReview(videoContext, scored, windowByStart, targetCount);
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
            VideoContext videoContext,
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
        double consensusScore = Math.min(5.0, window.consensusRatio() * 6.5);
        double spikeScore = Math.min(4.5, Math.max(0.0, window.peakWindowRatio() - 1.0) * 2.8);
        double keywordFocusScore = Math.min(3.8, window.keywordConcentration() * 5.5);
        double keywordShiftScore = calculateKeywordShiftScore(window, previous, next);
        double transitionScore = calculateTransitionScore(window, previous, next, averageMessages, averageUsers);
        String category = determineCategory(window);
        String reactionLabel = categoryLabel(category);
        boolean hardRejected = shouldHardReject(window);
        double negativePenalty = calculateNegativePenalty(window);
        double edgePenalty = calculateEdgePenalty(window.startSeconds(), firstWindowStart, lastWindowStart, videoContext.durationSeconds());

        double intensityScore = densityScore
                + userScore
                + burstScore
                + zScoreBoost
                + laughScore
                + surpriseScore
                + hypeScore
                + tensionScore
                + repetitionScore
                + punctuationScore
                + consensusScore
                + spikeScore;
        String sceneLabel = determineSceneLabel(videoContext, window, category, reactionLabel, intensityScore, transitionScore);

        double editabilityScore = Math.min(
                20.0,
                (messageVarietyScore * 2.2)
                        + (balanceScore * 1.8)
                        + Math.min(4.0, window.representativeMessage().isBlank() ? 0.0 : 4.0)
                        + Math.min(5.0, transitionScore * 0.65)
                        + (keywordFocusScore * 1.2)
                        + (keywordShiftScore * 1.4)
        );

        double totalScore = ((intensityScore * 0.55) + (transitionScore * 0.20) + (editabilityScore * 0.25))
                * edgePenalty
                * negativePenalty;
        if (hardRejected) {
            totalScore *= 0.18;
        }

        String reasonSummary = buildReasonSummary(window, sceneLabel, reactionLabel, intensityScore, transitionScore, editabilityScore);
        String description = buildDescription(window, sceneLabel);

        return new WindowScore(
                videoContext.videoNo(),
                window.startSeconds(),
                window.startSeconds() + WINDOW_SECONDS,
                totalScore,
                intensityScore,
                transitionScore,
                editabilityScore,
                category,
                reactionLabel,
                sceneLabel,
                description,
                reasonSummary,
                window.representativeMessage(),
                messageZScore,
                burstZScore,
                hardRejected
        );
    }

    private List<WindowScore> enrichWithLlmReview(VideoContext videoContext, List<WindowScore> scored, Map<Integer, WindowStats> windowByStart, int targetCount) {
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
                        return ollamaAnalyzerService.analyzeHighlight(buildHighlightPayload(videoContext, candidate, window))
                                .map(decision -> Map.entry(candidate.startSeconds(), decision))
                                .flux();
                    }, LLM_REVIEW_CONCURRENCY)
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .block(LLM_REVIEW_TIMEOUT);
        } catch (IllegalStateException error) {
            Throwable cause = error.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("[Vod-Analyzer] Timed out during LLM highlight review for videoNo={}, fallback to heuristic ranking.", videoContext.videoNo());
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

    private HighlightPromptPayload buildHighlightPayload(VideoContext videoContext, WindowScore score, WindowStats window) {
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
                videoContext.videoNo(),
                videoContext.title(),
                videoContext.category(),
                videoContext.durationSeconds(),
                Math.min(1.0, Math.max(0.0, (double) score.startSeconds() / Math.max(videoContext.durationSeconds(), WINDOW_SECONDS))),
                score.startSeconds(),
                score.endSeconds(),
                window.messageCount(),
                window.uniqueUsers(),
                score.messageZScore() <= 0 ? 1.0 : 1.0 + score.messageZScore(),
                score.messageZScore(),
                score.burstZScore(),
                window.consensusRatio(),
                window.peakWindowRatio(),
                window.keywordConcentration(),
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
                    score.sceneLabel(),
                    score.description(),
                    decisionReasoning + " | " + score.reasonSummary(),
                    true
            );
        }

        String normalizedCategory = normalizeEditorialCategory(decision.category());
        String normalizedSceneLabel = normalizeSceneLabel(decision.sceneLabel(), normalizedCategory, score.sceneLabel());
        String summary = (decision.summary() == null || decision.summary().isBlank())
                ? "하이라이트 후보 구간입니다."
                : decision.summary().trim();
        double intensityBoost = 1.0 + ((Math.max(1, Math.min(10, decision.intensity())) - 5) * 0.05);
        return score.withDecision(
                (score.score() + 2.4) * intensityBoost,
                normalizedCategory,
                normalizedCategory,
                normalizedSceneLabel,
                summary,
                decisionReasoning + " | " + score.reasonSummary(),
                false
            );
    }

    private String normalizeSceneLabel(String sceneLabel, String normalizedCategory, String fallback) {
        if (sceneLabel == null || sceneLabel.isBlank() || sceneLabel.equals("null")) {
            return fallback != null && !fallback.isBlank() ? fallback : normalizedCategory;
        }
        String trimmed = sceneLabel.trim();
        if (trimmed.length() > 20) {
            return trimmed.substring(0, 20).trim();
        }
        return trimmed;
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

    private double calculateEdgePenalty(int startSeconds, int firstWindowStart, int lastWindowStart, int durationSeconds) {
        double positionRatio = durationSeconds <= 0 ? 0.5 : (double) startSeconds / durationSeconds;
        if (positionRatio <= 0.03 || positionRatio >= 0.97) {
            return 0.54;
        }
        if (positionRatio <= 0.08 || positionRatio >= 0.92) {
            return 0.72;
        }
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

    private double calculateKeywordShiftScore(WindowStats current, WindowStats previous, WindowStats next) {
        Set<String> currentTop = current.topKeywordSet(3);
        if (currentTop.isEmpty()) {
            return 0.0;
        }

        double score = current.keywordConcentration() >= 0.28 ? 1.1 : 0.4;
        if (previous != null && Collections.disjoint(currentTop, previous.topKeywordSet(3))) {
            score += 1.4;
        }
        if (next != null && !Collections.disjoint(currentTop, next.topKeywordSet(3))) {
            score += 0.9;
        }
        return Math.min(3.8, score);
    }

    private String determineSceneLabel(
            VideoContext videoContext,
            WindowStats window,
            String category,
            String reactionLabel,
            double intensityScore,
            double transitionScore
    ) {
        String text = String.join(" ",
                safeText(videoContext.title(), ""),
                safeText(videoContext.category(), ""),
                safeText(window.representativeMessage(), ""),
                String.join(" ", window.topKeywords(5).keySet()),
                String.join(" ", window.topMessages(5).keySet())
        ).toLowerCase(Locale.ROOT);

        boolean hasGacha = containsAny(text, GACHA_TOKENS);
        boolean hasFlex = containsAny(text, FLEX_TOKENS);
        boolean hasDisaster = containsAny(text, DISASTER_TOKENS);
        boolean hasClutch = containsAny(text, CLUTCH_TOKENS);
        boolean hasChatMeta = containsAny(text, CHAT_TOKENS);

        if ((category.equals("운") || category.equals("WONDER") || category.equals("HYPE")) && hasGacha && hasFlex) {
            return "비틱";
        }
        if ((category.equals("대참사") || category.equals("TENSION")) && hasDisaster && transitionScore >= 2.0) {
            return "억까";
        }
        if ((category.equals("슈퍼플레이") || category.equals("HYPE") || category.equals("WONDER")) && hasClutch) {
            return transitionScore >= 2.5 ? "역전각" : "클러치";
        }
        if ((category.equals("소통") || category.equals("LAUGH") || category.equals("HOT_MOMENT")) && hasChatMeta && window.consensusRatio() >= 0.24) {
            return "채팅폭주";
        }
        if ((category.equals("LAUGH") || category.equals("WONDER")) && window.laughCount() >= 2 && window.surpriseCount() >= 2) {
            return "어이없음";
        }
        if (category.equals("슈퍼플레이")) {
            return "슈퍼플레이";
        }
        if (category.equals("대참사")) {
            return "대참사";
        }
        if (category.equals("운")) {
            return intensityScore >= 14.0 ? "행운 폭발" : "행운 장면";
        }
        if (category.equals("소통")) {
            return window.consensusRatio() >= 0.2 ? "채팅 합주" : "소통 타임";
        }
        if (category.equals("HYPE")) {
            return "분위기 폭발";
        }
        if (category.equals("WONDER")) {
            return reactionLabel.equals("놀람") && intensityScore >= 12.0 ? "반응 폭발" : reactionLabel;
        }
        return reactionLabel;
    }

    private String buildDescription(WindowStats window, String sceneLabel) {
        return String.format(
                "%s 장면입니다. 채팅 %d개와 참여자 %d명이 동시에 반응했습니다.",
                sceneLabel,
                window.messageCount(),
                window.uniqueUsers()
        );
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
            String sceneLabel,
            String reactionLabel,
            double intensityScore,
            double transitionScore,
            double editabilityScore
    ) {
        List<String> reasons = new ArrayList<>();

        reasons.add(String.format("%s 흐름이 보입니다.", sceneLabel));
        reasons.add(String.format("채팅 %d개 · 참여자 %d명", window.messageCount(), window.uniqueUsers()));

        if (window.burstSignal() >= 3.0) {
            reasons.add("감탄·반복 반응이 짧게 몰렸습니다.");
        }
        if (window.repeatedMessageCount() >= 2) {
            reasons.add("비슷한 메시지가 반복돼 장면 맥락이 또렷합니다.");
        }
        if (transitionScore >= 4.5) {
            reasons.add("직전 구간보다 분위기 전환이 큽니다.");
        }
        if (editabilityScore >= 8.0) {
            reasons.add("짧게 잘라 쓰기 좋은 편집 포인트입니다.");
        }

        if (intensityScore >= 12.0) {
            reasons.add("반응 강도가 높아 우선 확인할 만합니다.");
        } else if (transitionScore >= 3.5) {
            reasons.add("큰 폭발은 아니어도 흐름 변화가 뚜렷합니다.");
        } else {
            reasons.add(String.format("%s 중심으로 반응이 살아난 구간입니다.", reactionLabel));
        }

        return String.join(" | ", reasons);
    }

    private boolean containsAny(String text, List<String> tokens) {
        return tokens.stream().anyMatch(text::contains);
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
        private volatile long lastUpdatedAt = System.nanoTime();

        void addChat(JsonNode chat) {
            int seconds = extractVideoSeconds(chat);
            int windowKey = (seconds / WINDOW_SECONDS) * WINDOW_SECONDS;
            WindowStats window = windows.computeIfAbsent(windowKey, WindowStats::new);
            window.record(chat);
            lastUpdatedAt = System.nanoTime();
        }

        Map<Integer, WindowStats> windows() {
            return windows;
        }

        boolean isQuietFor(Duration duration) {
            return System.nanoTime() - lastUpdatedAt >= duration.toNanos();
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
        private final Map<String, Set<String>> messageSenders = new HashMap<>();
        private final int[] subWindowMessageCounts = new int[Math.max(1, WINDOW_SECONDS / SPIKE_BUCKET_SECONDS)];
        private String latestMessage = "";

        private WindowStats(int startSeconds) {
            this.startSeconds = startSeconds;
        }

        private void record(JsonNode chat) {
            messageCount++;

            String senderId = chat.path("userIdHash").asText(chat.path("senderId").asText(""));
            int seconds = extractVideoSeconds(chat);
            int offsetSeconds = Math.max(0, Math.min(WINDOW_SECONDS - 1, seconds - startSeconds));
            int subWindowIndex = Math.min(subWindowMessageCounts.length - 1, offsetSeconds / SPIKE_BUCKET_SECONDS);
            subWindowMessageCounts[subWindowIndex]++;
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
                if (!senderId.isBlank()) {
                    messageSenders.computeIfAbsent(normalized, ignored -> ConcurrentHashMap.newKeySet()).add(senderId);
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
            return Math.min(1.0, (double) repeatedMessageCount / messageCount);
        }

        private double consensusRatio() {
            if (uniqueUsers == 0 || messageSenders.isEmpty()) {
                return 0.0;
            }
            int maxConsensus = messageSenders.values().stream()
                    .mapToInt(Set::size)
                    .max()
                    .orElse(0);
            return Math.min(1.0, (double) maxConsensus / uniqueUsers);
        }

        private double peakWindowRatio() {
            if (messageCount == 0) {
                return 0.0;
            }
            double averagePerSlice = (double) messageCount / subWindowMessageCounts.length;
            int peakSlice = Arrays.stream(subWindowMessageCounts).max().orElse(0);
            if (averagePerSlice <= 0.0) {
                return 0.0;
            }
            return peakSlice / averagePerSlice;
        }

        private double keywordConcentration() {
            if (keywordCounts.isEmpty()) {
                return 0.0;
            }
            int totalKeywords = keywordCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (totalKeywords == 0) {
                return 0.0;
            }
            int topKeywordCount = keywordCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            return (double) topKeywordCount / totalKeywords;
        }

        private Set<String> topKeywordSet(int limit) {
            if (keywordCounts.isEmpty()) {
                return Set.of();
            }
            return keywordCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
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
            String sceneLabel,
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
                String updatedSceneLabel,
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
                    updatedSceneLabel,
                    updatedDescription,
                    updatedReasonSummary,
                    topMessage,
                    messageZScore,
                    burstZScore,
                    hardRejected || rejectedByLlm
            );
        }
    }

    private record VideoContext(
            String videoNo,
            String title,
            String category,
            int durationSeconds
    ) {
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static final class FinalizeThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "vod-highlight-finalizer");
            thread.setDaemon(true);
            return thread;
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
