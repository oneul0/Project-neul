package com.neul.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 감정 분석 완료 메시지.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalyzedChatMessage {
    private String messageId;
    private String roomId;

    @Builder.Default
    private String messageType = "CHAT";

    private String content;
    private String sender;
    private String senderId; // Added for Phase 23
    private Map<String, Double> emotionScores;
    private List<String> keywords;
    private Map<String, String> keywordGroups; // Keyword -> Representative Name
    private LocalDateTime timestamp; // 원본 채팅 발생 시간
    private LocalDateTime analyzedAt; // 분석 완료 시간
    private boolean isAmbiguous; // LLM 정밀 분석 필요 여부

    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
