package com.gak.v2.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2SimilarHighlightAlert {
    private String roomId;
    private Long highlightId;
    private String videoNo;
    private String sceneLabel;
    private String category;
    private String reasonSummary;
    private double similarity;
    private String trigger;       // "positive_spike" | "negative_spike"
    private LocalDateTime detectedAt;
}
