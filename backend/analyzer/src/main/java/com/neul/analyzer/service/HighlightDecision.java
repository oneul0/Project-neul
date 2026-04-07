package com.neul.analyzer.service;

public record HighlightDecision(
        boolean isHighlight,
        String category,
        String sceneLabel,
        String summary,
        int intensity,
        String reasoning
) {

    public static HighlightDecision fallback(String reasoning) {
        return new HighlightDecision(false, "판단보류", "판단보류", "하이라이트 근거가 부족합니다.", 3, reasoning);
    }
}
