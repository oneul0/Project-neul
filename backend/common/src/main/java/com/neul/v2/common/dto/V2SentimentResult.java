package com.neul.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2SentimentResult {
    private String messageId;
    private String roomId;
    private String senderId;
    private double positiveScore;
    private double negativeScore;
    private double neutralScore;
    private double valence;
    private double arousal;
    private Map<String, Double> emotionScores;
    private LocalDateTime analyzedAt;
}
