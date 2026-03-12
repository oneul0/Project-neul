package com.neul.analyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감정 분석 완료 메시지 (analyzed-chat-topic으로 발행).
 * CHAT: Gemini 분석 결과 포함 / DONATION, SUBSCRIPTION: 분석 없이 패스스루.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalyzedChatMessage {
    private String messageId;
    private String roomId;

    /** 이벤트 유형: CHAT / DONATION / SUBSCRIPTION */
    @Builder.Default
    private String messageType = "CHAT";

    // CHAT 필드
    private String content;
    private String sender;
    private Emotion emotion; // DONATION/SUBSCRIPTION은 null
    private LocalDateTime analyzedAt;

    // DONATION 필드 (passthrough)
    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    // SUBSCRIPTION 필드 (passthrough)
    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
