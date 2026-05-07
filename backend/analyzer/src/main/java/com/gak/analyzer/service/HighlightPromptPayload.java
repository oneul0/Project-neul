package com.gak.analyzer.service;

public record HighlightPromptPayload(
        String videoNo,
        String videoTitle,
        String videoCategory,
        int durationSeconds,
        double progressRatio,
        int startSeconds,
        int endSeconds,
        int messageCount,
        int uniqueUsers,
        double densityRatio,
        double zScore,
        double burstScore,
        double consensusRatio,
        double peakWindowRatio,
        double keywordConcentration,
        double repeatedRatio,
        double dominantSenderRatio,
        double goodbyeRatio,
        String keywordSummary,
        String negativeSignals,
        String chatBundle,
        // RAG few-shot 예시 (없으면 빈 문자열)
        String fewShotExamples,
        // 신호 비율 (few-shot 요청용)
        double laughRatio,
        double hypeRatio,
        double surpriseRatio,
        double tensionRatio,
        double uniqueUserRatio,
        String emotionDominance
) {
}
