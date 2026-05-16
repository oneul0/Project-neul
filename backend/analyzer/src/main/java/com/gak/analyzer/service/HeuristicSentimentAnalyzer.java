package com.gak.analyzer.service;

import com.gak.common.dto.AnalyzedChatMessage;
import com.gak.common.dto.RawChatMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 단순 키워드 매칭을 통한 초고속 감정 분석기 (Fast-Path).
 * LLM 분석 전 즉각적인 UI 피드백을 위해 사용됩니다.
 */
@Service
public class HeuristicSentimentAnalyzer {

    private static final Map<String, List<String>> EMOTION_KEYWORDS = new HashMap<>();

    static {

        EMOTION_KEYWORDS.put("JOY", Arrays.asList("ㅋ", "ㅎ", "ㄱㅇㅇ", "귀여워", "나이스", "오예", "웃기네", "LUL", "LOL", "축하", "기뻐"));
        EMOTION_KEYWORDS.put("HOPE", Arrays.asList("ㅇㅈ", "가즈아", "할수있다", "기대", "레전드", "대박", "추천", "기다림", "ㅎㅇㅌ", "화이팅"));
        EMOTION_KEYWORDS.put("SADNESS", Arrays.asList("ㅠ", "ㅜ", "ㅠㅠ", "ㅜㅜ", "슬퍼", "아쉽다", "안타깝네", "비보", "절망", "속상해"));
        EMOTION_KEYWORDS.put("ANGER", Arrays.asList("ㅡㅡ", "빡치네", "적당히", "그만", "화나", "짜증", "ㅗ", "노답", "어이없네", "ㅁㅊ"));
        EMOTION_KEYWORDS.put("WONDER", Arrays.asList("?", "!", "ㄷㄷ", "와", "허얼", "진짜", "실화냐", "뭐야", "소름", "Woah", "Wow"));
        EMOTION_KEYWORDS.put("DISGUST", Arrays.asList("웩", "극혐", "우욱", "더러워", "싫어", "거부감", "에바", "토나와"));
    }

    public AnalyzedChatMessage analyze(RawChatMessage msg) {
        String content = msg.getContent() != null ? msg.getContent() : "";
        Map<String, Integer> emotionCounts = new HashMap<>();
        
        // 키워드 매칭 개수 집계
        for (Map.Entry<String, List<String>> entry : EMOTION_KEYWORDS.entrySet()) {
            int count = 0;
            for (String kw : entry.getValue()) {
                if (content.contains(kw)) {
                    count++;
                }
            }
            if (count > 0) {
                emotionCounts.put(entry.getKey(), count);
            }
        }

        String foundEmotion = "NEUTRAL";
        boolean isAmbiguous = true;

        if (!emotionCounts.isEmpty()) {
            // 가장 많이 매칭된 감정 찾기
            List<Map.Entry<String, Integer>> sorted = emotionCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .collect(Collectors.toList());
            
            Map.Entry<String, Integer> top = sorted.get(0);
            foundEmotion = top.getKey();
            
            // 모호성 판별: 
            // 1. 매칭된 키워드가 1개뿐이거나
            // 2. 1위와 2위의 차이가 미미하거나 (동률인 경우 등)
            if (sorted.size() > 1) {
                isAmbiguous = (top.getValue() - sorted.get(1).getValue()) < 1;
            } else {
                // 키워드 하나만으로 확신하기 어려운 경우 (예: "진짜?" -> WONDER vs ㅋ -> JOY)
                isAmbiguous = top.getValue() <= 1;
            }
        }

        Map<String, Double> scores = createDefaultScores(foundEmotion);

        return AnalyzedChatMessage.builder()
                .messageId(msg.getMessageId())
                .roomId(msg.getRoomId())
                .messageType(msg.getMessageType())
                .content(msg.getContent())
                .sender(msg.getSender())
                .senderId(msg.getSenderId())
                .emotionScores(scores)
                .isAmbiguous(isAmbiguous) // Confidence logic applied
                .timestamp(msg.getTimestamp())
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private Map<String, Double> createDefaultScores(String emotionType) {
        Map<String, Double> scores = new HashMap<>();
        scores.put("JOY", 0.0);
        scores.put("HOPE", 0.0);
        scores.put("NEUTRAL", 0.0);
        scores.put("SADNESS", 0.0);
        scores.put("ANGER", 0.0);
        scores.put("WONDER", 0.0);
        scores.put("DISGUST", 0.0);
        scores.put(emotionType, 1.0);
        return scores;
    }
}
