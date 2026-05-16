package com.gak.core_api.domain.chat.entity;

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
    private String sceneLabel;
    private String description;
    private String reasonSummary;
    private String topMessage;

    // 신호 비율 (V6)
    private Double laughRatio;
    private Double hypeRatio;
    private Double surpriseRatio;
    private Double tensionRatio;
    private Double densityRatio;
    private Double uniqueUserRatio;
    private String emotionDominance;
    private String keywordSummary;

    // RAG 임베딩 (V7) — embedding 컬럼은 vector 타입이라 R2DBC 직접 매핑 불가,
    // embeddingText만 엔티티에서 관리하고 embedding 업데이트는 DatabaseClient raw SQL로 처리
    private String embeddingText;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
