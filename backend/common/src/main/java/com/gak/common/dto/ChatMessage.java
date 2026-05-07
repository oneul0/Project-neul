package com.gak.common.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ChatMessage {
	private String messageId;
	private String roomId;
	private String sender;
	private String content;
	private LocalDateTime timestamp;
}