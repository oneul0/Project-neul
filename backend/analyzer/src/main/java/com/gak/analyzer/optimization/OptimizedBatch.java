package com.gak.analyzer.optimization;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * {@link ChatOptimizer#optimize} 실행 결과를 담는 DTO.
 *
 * <p>
 * 압축된 채팅 목록과 함께 최적화 통계(원본 수, 필터링 수, 압축률)를 포함합니다.
 * 통계 필드는 로그 출력 및 향후 모니터링 지표 수집에 활용됩니다.
 */
@Getter
@Builder
public class OptimizedBatch {

    /** 필터링 + 압축이 완료된 채팅 대표 메시지 목록 */
    private final List<CompressedChat> compressedChats;

    /** 최적화 전 원본 메시지 수 */
    private final int originalCount;

    /** 필터링(스팸/짧은 메시지)으로 제거된 수 */
    private final int filteredCount;

    /**
     * 압축률 (%).
     * {@code (1 - 최종 전달 수 / 원본 수) * 100}
     * 값이 클수록 LLM 토큰 절감 효과가 큼.
     */
    private final double compressionRatio;
}
