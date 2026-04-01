package com.neul.common.dto;

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
    private String description;
    private String reasonSummary;
    private String topMessage;
}
