package com.neul.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2AggregateFrame {
    private String roomId;
    private LocalDateTime emittedAt;
    private double balance;
    private MentalBufferState mentalBuffer;
    private Map<String, Object> trustSummary;
    private List<AnchorChat> anchors;
    private List<String> keywords;
    private String topicLabel;
    private NarrativeBriefing briefing;
    private Map<String, Object> stats;
}
