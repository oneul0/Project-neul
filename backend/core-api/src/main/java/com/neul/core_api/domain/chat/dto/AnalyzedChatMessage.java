package com.neul.core_api.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 분석 완료 메시지 DTO.
 * CHAT: emotion 포함 / DONATION, SUBSCRIPTION: 타입별 필드 포함, emotion=null
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

    // CHAT
    private String content;
    private String sender;
    private Emotion emotion;
    private LocalDateTime analyzedAt;

    // DONATION
    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    // SUBSCRIPTION
    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
