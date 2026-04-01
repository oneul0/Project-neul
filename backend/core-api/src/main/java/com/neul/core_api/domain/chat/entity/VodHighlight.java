package com.neul.core_api.domain.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * VOD 하이라이트 구간 영속성 엔티티.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("vod_highlights")
public class VodHighlight {
    @Id
    private Long id;
    
    private String videoNo;
    private Integer startSeconds;
    private Integer endSeconds;
    private Double highlightScore;
    private Double intensityScore;
    private Double transitionScore;
    private Double editabilityScore;
    
    private String category;
    private String reactionLabel;
    private String description;
    private String reasonSummary;
    private String topMessage;
    
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
