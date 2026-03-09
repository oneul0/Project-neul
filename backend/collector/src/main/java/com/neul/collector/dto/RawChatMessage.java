package com.neul.collector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chzzk로부터 수신한 원본 이벤트 메시지.
 * CHAT / DONATION / SUBSCRIPTION 세 가지 타입을 단일 DTO로 처리.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RawChatMessage {

    /** 메시지 고유 ID (UUID) */
    private String messageId;

    /** Chzzk 채널 ID (roomId 역할) */
    private String roomId;

    /** 이벤트 유형: CHAT / DONATION / SUBSCRIPTION */
    @Builder.Default
    private String messageType = "CHAT";

    // ── CHAT 전용 필드 ──────────────────────────────────────────
    /** 채팅 작성자 닉네임 */
    private String sender;

    /** 채팅 내용 */
    private String content;

    /** 발송 시각 (Chzzk messageTime 기반, ms epoch → LocalDateTime 변환) */
    private LocalDateTime timestamp;

    /** 작성자 권한 (streamer / common_user / streaming_channel_manager 등) */
    private String userRoleCode;

    // ── DONATION 전용 필드 ──────────────────────────────────────
    /** 후원 종류: CHAT / VIDEO */
    private String donationType;

    /** 후원자 닉네임 */
    private String donatorNickname;

    /** 후원 금액 (원) */
    private String payAmount;

    /** 후원 메시지 */
    private String donationText;

    // ── SUBSCRIPTION 전용 필드 ────────────────────────────────
    /** 구독자 닉네임 */
    private String subscriberNickname;

    /** 구독 티어 (1 / 2) */
    private Integer tierNo;

    /** 구독 브랜드명 */
    private String tierName;

    /** 구독 기간 (월) */
    private Integer month;
}
