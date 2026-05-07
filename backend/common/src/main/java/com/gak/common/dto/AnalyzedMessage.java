package com.gak.common.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AnalyzedMessage {
	private String messageId;
	private String roomId;
	private String content;
	private Emotion emotion;
	private LocalDateTime analyzedAt;

	@Getter
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Emotion {
		private String type; // POSITIVE, NEGATIVE, NEUTRAL
		private Double score; // -1.0 ~ 1.0
	}
}