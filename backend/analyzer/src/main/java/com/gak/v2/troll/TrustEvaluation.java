package com.gak.v2.troll;

import com.gak.v2.common.dto.V2RawChatMessage;
import com.gak.v2.common.dto.V2TrollResult;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Builder
class TrustEvaluation {
    private final double trustScore;
    private final String trustGrade;
    private final double spamScore;
    private final boolean filtered;
    private final List<String> reasons;
    private final long messageCount;
    private final long negativeCount;
    private final long spamCount;
    private final long consecutiveNegativeCount;
    private final long shortIntervalCount;
    private final double recentJoinPenalty;
    private final String lastContent;
    private final String lastNormalizedContent;
    private final LocalDateTime lastSeenAt;

    Map<String, Object> toRedisMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trustScore", trustScore);
        map.put("trustGrade", trustGrade);
        map.put("messageCount", messageCount);
        map.put("negativeCount", negativeCount);
        map.put("spamCount", spamCount);
        map.put("consecutiveNegativeCount", consecutiveNegativeCount);
        map.put("shortIntervalCount", shortIntervalCount);
        map.put("recentJoinPenalty", recentJoinPenalty);
        map.put("lastContent", lastContent);
        map.put("lastNormalizedContent", lastNormalizedContent);
        map.put("lastSeenAt", lastSeenAt);
        return map;
    }

    V2TrollResult toResult(V2RawChatMessage message) {
        return V2TrollResult.builder()
                .messageId(message.getMessageId())
                .roomId(message.getRoomId())
                .senderId(message.getSenderId())
                .trustScore(trustScore)
                .trustGrade(trustGrade)
                .spamScore(spamScore)
                .isFiltered(filtered)
                .reasons(reasons)
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
