package com.neul.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserTrustProfile {
    private String roomId;
    private String senderId;
    private long messageCount;
    private long negativeCount;
    private long spamCount;
    private double recentJoinPenalty;
    private double trustScore;
    private String trustGrade;
    private LocalDateTime lastSeenAt;
}
