package com.neul.collector.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawChatMessage {
    private String messageId;
    private String roomId;
    private String sender;
    private String content;
    private LocalDateTime timestamp;
}
