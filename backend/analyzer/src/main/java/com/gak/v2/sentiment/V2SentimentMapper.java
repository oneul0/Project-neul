package com.gak.v2.sentiment;

import com.gak.common.dto.AnalyzedChatMessage;
import com.gak.common.dto.RawChatMessage;
import com.gak.v2.common.dto.V2RawChatMessage;
import com.gak.v2.common.dto.V2SentimentResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class V2SentimentMapper {

    public RawChatMessage toRawChatMessage(V2RawChatMessage message) {
        return RawChatMessage.builder()
                .messageId(message.getMessageId())
                .roomId(message.getRoomId())
                .senderId(message.getSenderId())
                .sender(message.getSender())
                .messageType(message.getMessageType())
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .userRoleCode(message.getUserRoleCode())
                .build();
    }

    public V2SentimentResult toSentimentResult(V2RawChatMessage original, AnalyzedChatMessage analyzed) {
        Map<String, Double> emotionScores = analyzed.getEmotionScores() == null
                ? Map.of("NEUTRAL", 1.0)
                : analyzed.getEmotionScores();

        double positiveScore = getPositiveScore(emotionScores);
        double negativeScore = getNegativeScore(emotionScores);
        double neutralScore = emotionScores.getOrDefault("NEUTRAL", 0.0);

        return V2SentimentResult.builder()
                .messageId(original.getMessageId())
                .roomId(original.getRoomId())
                .senderId(original.getSenderId())
                .positiveScore(positiveScore)
                .negativeScore(negativeScore)
                .neutralScore(neutralScore)
                .valence(positiveScore - negativeScore)
                .arousal(Math.max(positiveScore, negativeScore))
                .emotionScores(emotionScores)
                .analyzedAt(analyzed.getAnalyzedAt() != null ? analyzed.getAnalyzedAt() : LocalDateTime.now())
                .build();
    }

    private double getPositiveScore(Map<String, Double> scores) {
        return scores.getOrDefault("JOY", 0.0)
                + scores.getOrDefault("HOPE", 0.0)
                + (scores.getOrDefault("WONDER", 0.0) * 0.5);
    }

    private double getNegativeScore(Map<String, Double> scores) {
        return scores.getOrDefault("ANGER", 0.0)
                + scores.getOrDefault("DISGUST", 0.0)
                + scores.getOrDefault("SADNESS", 0.0);
    }
}
