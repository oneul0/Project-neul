package com.neul.analyzer.service;

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
        String chatBundle
) {
}
