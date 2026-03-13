package com.neul.analyzer.optimization;

import com.neul.common.dto.RawChatMessage;

import java.util.List;

/**
 * Chat 최적화 엔진의 포트(Port) 인터페이스.
 *
 * <p>
 * 현재는 {@link com.neul.analyzer.optimization.java.JavaChatOptimizer}가 구현체로 동작하며,
 * 추후 JNI를 통해 Rust 네이티브
 * 모듈({@link com.neul.analyzer.optimization.jni.RustChatOptimizer})로
 * 교체할 수 있도록 이 인터페이스를 경계(Port)로 사용합니다.
 *
 * <p>
 * 전환 방법: {@code application.yaml}의 {@code app.optimizer.engine} 값을
 * {@code java} → {@code rust} 로 변경.
 */
public interface ChatOptimizer {

    /**
     * 원본 채팅 메시지 배치를 최적화합니다.
     * 스팸 필터링(A) → 중복 압축(B) 순서로 처리합니다.
     *
     * @param rawMessages Kafka에서 수신한 원본 채팅 메시지 목록
     * @return 최적화된 배치 결과 (압축 메시지 목록 + 필터링 통계)
     */
    OptimizedBatch optimize(List<RawChatMessage> rawMessages);
}
