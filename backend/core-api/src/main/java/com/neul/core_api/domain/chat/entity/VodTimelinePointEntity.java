package com.neul.core_api.domain.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("vod_timeline_points")
public class VodTimelinePointEntity {
    @Id
    private Long id;

    private String videoNo;
    private Integer startSeconds;
    private Integer endSeconds;
    private Integer messageCount;
    private Integer participantCount;
    private Double activityScore;
    private String category;
    private String topMessage;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
