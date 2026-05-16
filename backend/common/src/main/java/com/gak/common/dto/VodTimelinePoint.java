package com.gak.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VodTimelinePoint {
    private String videoNo;
    private Integer startSeconds;
    private Integer endSeconds;
    private Integer messageCount;
    private Integer participantCount;
    private Double activityScore;
    private String category;
    private String topMessage;
}
