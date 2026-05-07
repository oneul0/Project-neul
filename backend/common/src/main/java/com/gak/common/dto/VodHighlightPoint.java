package com.gak.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VodHighlightPoint {
    private String videoNo;
    private int startSeconds;
    private int endSeconds;
    private double highlightScore;
    private double intensityScore;
    private double transitionScore;
    private double editabilityScore;
    private String category;
    private String reactionLabel;
    private String sceneLabel;
    private String description;
    private String reasonSummary;
    private String topMessage;

    // 신호 비율 (WindowStats에서 정규화)
    private double laughRatio;
    private double hypeRatio;
    private double surpriseRatio;
    private double tensionRatio;

    // 밀도 지표 (채널 규모 정규화)
    private double densityRatio;
    private double uniqueUserRatio;

    // 장면 요약
    private String emotionDominance;
    private String keywordSummary;
}
