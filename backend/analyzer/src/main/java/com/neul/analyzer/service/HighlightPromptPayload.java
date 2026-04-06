package com.neul.analyzer.service;

public record HighlightPromptPayload(
        String videoNo,
        int startSeconds,
        int endSeconds,
        int messageCount,
        int uniqueUsers,
        double densityRatio,
        double zScore,
        double burstScore,
        double repeatedRatio,
        double dominantSenderRatio,
        double goodbyeRatio,
        String keywordSummary,
        String negativeSignals,
        String chatBundle
) {
}
