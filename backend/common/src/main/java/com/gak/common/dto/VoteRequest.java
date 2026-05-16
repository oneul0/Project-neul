package com.gak.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 투표(Vote) 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoteRequest {
    private String roomId;
    private String title;
    private List<String> options;
    private boolean active;
}
