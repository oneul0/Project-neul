package com.neul.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 실시간 키워드 집계 데이터 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeywordUpdate {
    private String roomId;
    private Map<String, Integer> keywords; // Keyword -> Count
    private Map<String, String> groupMapping; // Keyword -> Representative Group Name
}
