package com.neul.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2TrollResult {
    private String messageId;
    private String roomId;
    private String senderId;
    private double trustScore;
    private String trustGrade;
    private double spamScore;
    private boolean isFiltered;
    private List<String> reasons;
    private LocalDateTime analyzedAt;
}
