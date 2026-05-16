package com.gak.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2RawChatMessage {
    private String messageId;
    private String roomId;
    private String senderId;
    private String sender;

    @Builder.Default
    private String messageType = "CHAT";

    private String content;
    private LocalDateTime timestamp;
    private String userRoleCode;
}
