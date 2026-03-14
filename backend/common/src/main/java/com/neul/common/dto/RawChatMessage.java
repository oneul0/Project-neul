package com.neul.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chzzk로부터 수신한 원본 이벤트 메시지.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawChatMessage {
    private String messageId;
    private String roomId;

    @Builder.Default
    private String messageType = "CHAT";

    private String sender;
    private String senderId; // Added for Phase 23
    private String content;
    private LocalDateTime timestamp;
    private String userRoleCode;

    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
