package com.gak.analyzer.optimization;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 중복 압축(Compression) 후의 대표 채팅 메시지 DTO.
 *
 * <p>
 * 동일하거나 유사한 내용의 채팅 N건을 1개로 묶어 표현합니다.
 * {@code count} 필드를 통해 원래 메시지 수를 추적하며,
 * LLM 프롬프트 구성 시 "내용 (N건)" 형태로 활용됩니다.
 */
@Getter
@Builder
public class CompressedChat {

    /** 그룹을 대표하는 원본 messageId */
    private final String representativeId;

    /** 그룹을 대표하는 발신자 ID */
    private final String representativeSenderId; // Added for Phase 23

    /** 방 ID */
    private final String roomId;

    /** 대표 메시지 원문 내용 */
    private final String content;

    /** 이 그룹에 묶인 원본 메시지 수 (압축 배율) */
    private final int count;

    /** 대표 메시지의 타임스탬프 */
    private final LocalDateTime timestamp;
}
