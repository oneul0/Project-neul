package com.neul.v2.troll;

import com.neul.v2.common.dto.V2RawChatMessage;
import com.neul.v2.common.dto.V2TrollResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class V2TrustScoreService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<V2TrollResult> evaluate(V2RawChatMessage message) {
        String key = profileKey(message.getRoomId(), message.getSenderId());

        return redisTemplate.opsForHash().entries(key)
                .collectMap(entry -> entry.getKey().toString(), Map.Entry::getValue)
                .defaultIfEmpty(Map.of())
                .flatMap(existing -> {
                    TrustEvaluation evaluation = calculate(message, existing);
                    return persistProfile(key, evaluation)
                            .thenReturn(evaluation.toResult(message));
                });
    }

    private Mono<Void> persistProfile(String key, TrustEvaluation evaluation) {
        return redisTemplate.opsForHash().putAll(key, evaluation.toRedisMap())
                .then(redisTemplate.expire(key, Duration.ofHours(12)))
                .then();
    }

    private TrustEvaluation calculate(V2RawChatMessage message, Map<String, Object> existing) {
        long messageCount = parseLong(existing.get("messageCount"));
        long negativeCount = parseLong(existing.get("negativeCount"));
        long spamCount = parseLong(existing.get("spamCount"));
        long consecutiveNegativeCount = parseLong(existing.get("consecutiveNegativeCount"));
        long shortIntervalCount = parseLong(existing.get("shortIntervalCount"));

        String lastContent = stringify(existing.get("lastContent"));
        String lastNormalizedContent = stringify(existing.get("lastNormalizedContent"));
        LocalDateTime lastSeenAt = parseDateTime(existing.get("lastSeenAt"));

        String content = safeTrim(message.getContent());
        String normalizedContent = normalizeContent(content);

        boolean negativeLike = isNegative(content);
        boolean hostileLike = isHostile(content);
        boolean repeatSameMessage = !normalizedContent.isBlank() && normalizedContent.equals(lastNormalizedContent);
        boolean shortInterval = isShortInterval(lastSeenAt, message.getTimestamp(), 3);
        boolean shortRepeat = repeatSameMessage && shortInterval;
        boolean burstSpam = shortInterval && content.length() <= 6;
        boolean spamLike = shortRepeat || burstSpam;

        long nextMessageCount = messageCount + 1;
        long nextNegativeCount = negativeCount + (negativeLike ? 1 : 0);
        long nextSpamCount = spamCount + (spamLike ? 1 : 0);
        long nextConsecutiveNegativeCount = negativeLike ? consecutiveNegativeCount + 1 : 0;
        long nextShortIntervalCount = shortInterval ? shortIntervalCount + 1 : 0;

        double roleBonus = roleBonus(message.getUserRoleCode());
        double experienceBonus = Math.min(nextMessageCount, 20) * 0.01;
        double negativeRatioPenalty = nextMessageCount == 0 ? 0.0 : ((double) nextNegativeCount / nextMessageCount) * 0.25;
        double spamPenalty = nextSpamCount * 0.12;
        double repeatPenalty = shortRepeat ? 0.18 : 0.0;
        double burstPenalty = nextShortIntervalCount >= 3 ? 0.10 : 0.0;
        double negativeBurstPenalty = nextConsecutiveNegativeCount >= 2 ? 0.12 : 0.0;
        double hostilePenalty = hostileLike ? 0.20 : 0.0;
        double recentJoinPenalty = nextMessageCount <= 3 && negativeLike ? 0.10 : 0.0;

        double trustScore = 0.62;
        trustScore += roleBonus;
        trustScore += experienceBonus;
        trustScore -= negativeRatioPenalty;
        trustScore -= spamPenalty;
        trustScore -= repeatPenalty;
        trustScore -= burstPenalty;
        trustScore -= negativeBurstPenalty;
        trustScore -= hostilePenalty;
        trustScore -= recentJoinPenalty;
        trustScore = clamp(trustScore, 0.0, 1.0);

        List<String> reasons = new ArrayList<>();
        if (negativeLike) {
            reasons.add("NEGATIVE_PATTERN");
        }
        if (hostileLike) {
            reasons.add("HOSTILE_PATTERN");
        }
        if (shortRepeat) {
            reasons.add("REPEATED_MESSAGE");
        }
        if (burstSpam) {
            reasons.add("SHORT_INTERVAL_BURST");
        }
        if (recentJoinPenalty > 0) {
            reasons.add("NEW_USER_NEGATIVE_BURST");
        }

        String trustGrade = toGrade(trustScore, nextMessageCount, nextSpamCount, nextNegativeCount);

        return TrustEvaluation.builder()
                .trustScore(trustScore)
                .trustGrade(trustGrade)
                .spamScore(spamLike ? 1.0 : 0.0)
                .filtered("TROLL_CANDIDATE".equals(trustGrade))
                .reasons(reasons)
                .messageCount(nextMessageCount)
                .negativeCount(nextNegativeCount)
                .spamCount(nextSpamCount)
                .consecutiveNegativeCount(nextConsecutiveNegativeCount)
                .shortIntervalCount(nextShortIntervalCount)
                .recentJoinPenalty(recentJoinPenalty)
                .lastContent(content)
                .lastNormalizedContent(normalizedContent)
                .lastSeenAt(message.getTimestamp() != null ? message.getTimestamp() : LocalDateTime.now())
                .build();
    }

    private String toGrade(double trustScore, long messageCount, long spamCount, long negativeCount) {
        if (messageCount >= 5 && trustScore >= 0.76 && spamCount == 0 && negativeCount <= 1) {
            return "FAN";
        }
        if (trustScore < 0.32 || spamCount >= 3) {
            return "TROLL_CANDIDATE";
        }
        return "NORMAL";
    }

    private boolean isNegative(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "노잼", "답답", "못하", "왜이래", "최악", "싫", "별로",
                "boring", "bad", "terrible", "worst");
    }

    private boolean isHostile(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "꺼져", "망해", "접어", "죽", "병", "멍청", "한심",
                "trash", "stupid", "idiot", "hate you");
    }

    private boolean containsAny(String content, String... tokens) {
        for (String token : tokens) {
            if (content.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isShortInterval(LocalDateTime lastSeenAt, LocalDateTime currentSeenAt, long seconds) {
        if (lastSeenAt == null || currentSeenAt == null) {
            return false;
        }
        return Duration.between(lastSeenAt, currentSeenAt).abs().toSeconds() <= seconds;
    }

    private double roleBonus(String userRoleCode) {
        if (userRoleCode == null || userRoleCode.isBlank()) {
            return 0.0;
        }

        String role = userRoleCode.toUpperCase(Locale.ROOT);
        if (role.contains("MANAGER") || role.contains("OWNER")) {
            return 0.15;
        }
        if (role.contains("SUB") || role.contains("FAN") || role.contains("FOLLOW")) {
            return 0.08;
        }
        return 0.0;
    }

    private String normalizeContent(String content) {
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[!?.~]+", "")
                .trim();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String profileKey(String roomId, String senderId) {
        return "v2:room:" + roomId + ":user:" + senderId;
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    private String stringify(Object value) {
        return value == null ? "" : value.toString();
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
