package com.gak.v2.common.dto;

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
public class NarrativeBriefing {
    private String roomId;
    private String summary;
    private double confidence;
    private LocalDateTime generatedAt;
    private LocalDateTime sourceWindowStart;
    private LocalDateTime sourceWindowEnd;
}
