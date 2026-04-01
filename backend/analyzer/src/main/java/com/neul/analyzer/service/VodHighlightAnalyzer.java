package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodCrawlCompletedEvent;
import com.neul.common.dto.VodAnalysisCompletedEvent;
import com.neul.common.dto.VodHighlightPoint;
import com.neul.common.dto.VodTimelinePoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VodHighlightAnalyzer {

    private static final int WINDOW_SECONDS = 30;
    private static final int MIN_HIGHLIGHTS = 5;
    private static final int MAX_HIGHLIGHTS = 24;

    private static final List<String> LAUGH_TOKENS = List.of("\u314b\u314b", "\u314e\u314e", "lol", "lmao", "rofl");
    private static final List<String> SURPRISE_TOKENS = List.of("\uc640", "\ud5c9", "\ub300\ubc15", "omg", "wtf");
    private static final List<String> HYPE_TOKENS = List.of("\ub808\uc804\ub4dc", "goat", "\uc9c0\ub9b0", "\ubbf8\uccd0", "\uc18c\ub984");
    private static final List<String> TENSION_TOKENS = List.of("\uc5b5\uae4c", "\uc2f8\uc6c0", "\ubd88\uc548", "\uc9d1\uc911", "\ubd84\ub178");

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Map<String, VideoAggregate> aggregates = new ConcurrentHashMap<>();

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
        int maxMessages = windows.stream().mapToInt(WindowStats::messageCount).max().orElse(1);
        int maxUsers = windows.stream().mapToInt(WindowStats::uniqueUsers).max().orElse(1);
        double maxBurst = windows.stream().mapToDouble(WindowStats::burstSignal).max().orElse(1.0);

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
                    averageMessages,
                    averageUsers,
                    averageBursts,
                    maxMessages,
                    maxUsers,
                    maxBurst
            ));
        }

        scored = scored.stream()
                .sorted(Comparator.comparingDouble(WindowScore::score).reversed())
                .toList();

        int targetCount = Math.min(MAX_HIGHLIGHTS, Math.max(MIN_HIGHLIGHTS, (int) Math.ceil(windows.size() * 0.12)));
        List<WindowScore> selected = selectDistributedHighlights(scored, targetCount);

        if (selected.size() < Math.min(MIN_HIGHLIGHTS, scored.size())) {
            selected = mergeUniqueByStartSeconds(selected, scored.subList(0, Math.min(MIN_HIGHLIGHTS, scored.size())));
        }

        return selected.stream()
                .sorted(Comparator.comparingInt(WindowScore::startSeconds))
                .toList();
    }

    private List<WindowScore> selectDistributedHighlights(List<WindowScore> scored, int targetCount) {
        if (scored.isEmpty()) {
            return List.of();
        }

        int bucketCount = Math.min(targetCount, Math.min(8, Math.max(4, targetCount / 2)));
        int maxStart = scored.stream().mapToInt(WindowScore::startSeconds).max().orElse(0);
        int bucketSize = Math.max(1, (maxStart + WINDOW_SECONDS) / bucketCount);
        int globalQuota = Math.max(0, targetCount - bucketCount);

        Map<Integer, WindowScore> selectedByStart = new LinkedHashMap<>();

        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int bucketStart = bucket * bucketSize;
            int bucketEnd = bucket == bucketCount - 1 ? Integer.MAX_VALUE : bucketStart + bucketSize;

            scored.stream()
                    .filter(candidate -> candidate.startSeconds() >= bucketStart && candidate.startSeconds() < bucketEnd)
                    .findFirst()
                    .ifPresent(candidate -> selectedByStart.putIfAbsent(candidate.startSeconds(), candidate));
        }

        if (globalQuota > 0) {
            for (WindowScore candidate : scored) {
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
            for (WindowScore candidate : scored) {
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
            double averageMessages,
            double averageUsers,
            double averageBursts,
            int maxMessages,
            int maxUsers,
            double maxBurst
    ) {
        double densityFactor = averageMessages == 0 ? 1.0 : ((double) window.messageCount() / averageMessages);
        double userFactor = averageUsers == 0 ? 1.0 : ((double) window.uniqueUsers() / averageUsers);
        double burstFactor = averageBursts == 0 ? 1.0 : (window.burstSignal() / averageBursts);

        double densityScore = Math.min(14.0, densityFactor * 4.4 + ((double) window.messageCount() / Math.max(1, maxMessages)) * 5.0);
        double userScore = Math.min(9.0, userFactor * 3.4 + ((double) window.uniqueUsers() / Math.max(1, maxUsers)) * 3.0);
        double burstScore = Math.min(8.0, burstFactor * 3.4 + (window.burstSignal() / Math.max(1.0, maxBurst)) * 2.6);
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

        double intensityScore = densityScore
                + userScore
                + burstScore
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

        double totalScore = (intensityScore * 0.55) + (transitionScore * 0.20) + (editabilityScore * 0.25);

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
                window.representativeMessage()
        );
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
        private final Set<String> seenUsers = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> normalizedCounts = new HashMap<>();
        private final Map<String, String> originalMessages = new HashMap<>();
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
            }

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
            String topMessage
    ) {
    }
}
