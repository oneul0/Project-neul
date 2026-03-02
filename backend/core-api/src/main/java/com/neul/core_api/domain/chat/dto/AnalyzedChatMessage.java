package com.neul.core_api.domain.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyzedChatMessage {
    private String messageId;
    private String roomId;
    private String content;
    private Emotion emotion;
    private LocalDateTime analyzedAt;
}
