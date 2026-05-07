package com.gak.core_api.domain.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDateTime;

/**
 * 분석 채팅 로그 엔티티.
 * CHAT 메시지 전용 저장 (DONATION, SUBSCRIPTION은 별도 이벤트로만 SSE 전달).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("analyzed_chats")
public class AnalyzedChat {

    @Id
    private Long id;

    @Column("message_id")
    private String messageId;

    /** Chzzk 채널 ID */
    @Column("room_id")
    private String roomId;

    @Column("content")
    private String content;

    @Column("sender")
    private String sender;
    @Column("sender_id")
    private String senderId; // Added for Phase 23

    @Column("emotion_type")
    private String emotionType;

    @Column("emotion_score")
    private Double emotionScore;

    @Column("analyzed_at")
    private LocalDateTime analyzedAt;
}
