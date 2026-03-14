package com.neul.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    private Emotion emotion;
    private List<String> keywords;
    private LocalDateTime analyzedAt;

    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
