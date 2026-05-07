package com.gak.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 1분 단위로 묶인 원본 채팅 배치.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawChatBatch {
    private String roomId;
    private List<RawChatMessage> messages;
    private LocalDateTime batchTime;
}
