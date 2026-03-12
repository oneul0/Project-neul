package com.neul.analyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chzzk로부터 수신한 원본 이벤트 메시지 (CHAT / DONATION / SUBSCRIPTION).
 * collector 모듈의 동명 DTO와 동일한 구조 - Kafka JSON 역직렬화용.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawChatMessage {
    private String messageId;
    private String roomId;

    /** 이벤트 유형: CHAT / DONATION / SUBSCRIPTION (기본값: CHAT) */
    @Builder.Default
    private String messageType = "CHAT";

    // CHAT 필드
    private String sender;
    private String content;
    private LocalDateTime timestamp;
    private String userRoleCode;

    // DONATION 필드
    private String donationType;
    private String donatorNickname;
    private String payAmount;
    private String donationText;

    // SUBSCRIPTION 필드
    private String subscriberNickname;
    private Integer tierNo;
    private String tierName;
    private Integer month;
}
