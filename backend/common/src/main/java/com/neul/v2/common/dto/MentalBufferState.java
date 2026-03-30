package com.neul.v2.common.dto;

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
public class MentalBufferState {
    private String roomId;
    private double emaPositive;
    private double emaNegative;
    private double rawPositive;
    private double rawNegative;
    private LocalDateTime updatedAt;
}
