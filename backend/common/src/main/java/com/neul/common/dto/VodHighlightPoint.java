package com.neul.common.dto;

import lombok.*;

/**
 * VOD 하이라이트 구간 정보 DTO.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VodHighlightPoint {
    private String videoNo;
    private int startSeconds;
    private int endSeconds;
    private double highlightScore;
    private String category;    // "JOY", "WONDER", "LAUGH", "HOT" 등
    private String description; // AI가 생성한 간단 코멘트
    private String topMessage;  // 해당 구간 대표 채팅
}
