package com.gak.analyzer.optimization.jni;

import com.gak.common.dto.RawChatMessage;
import com.gak.analyzer.optimization.ChatOptimizer;
import com.gak.analyzer.optimization.OptimizedBatch;
import com.gak.analyzer.optimization.java.JavaChatOptimizer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * {@link ChatOptimizer} Rust JNI 어댑터 (Stub).
 *
 * <p>
 * 이 클래스는 Rust 네이티브 모듈이 개발되었을 때 JNI를 통해
 * {@code optimize()} 연산을 Java 힙 외부에서 수행하기 위한 교체 지점입니다.
 *
 * <h3>현재 상태 (Stub)</h3>
 * <ul>
 * <li>네이티브 라이브러리({@code gak_optimizer.dll} / {@code libgak_optimizer.so}) 로드를
 * 시도합니다.</li>
 * <li>로드 실패 시 {@link JavaChatOptimizer}로 자동 위임(Fallback)합니다.</li>
 * <li>로드 성공하더라도 {@link #optimizeNative(String)}는 아직 미구현 상태로, Fallback이
 * 동작합니다.</li>
 * </ul>
 *
 * <h3>Rust 모듈 개발 후 구현 순서</h3>
 * <ol>
 * <li>Rust 측: {@code jni} crate로
 * {@code Java_com_gak_analyzer_optimization_jni_RustChatOptimizer_optimizeNative}
 * 시그니처 구현</li>
 * <li>빌드: {@code cargo build --release} → {@code gak_optimizer.dll / .so}
 * 생성</li>
 * <li>배포: {@code src/main/resources/native/} 경로에 라이브러리 위치</li>
 * <li>이 클래스의 {@link #optimize} 메서드에서 JSON 직렬화/역직렬화 구현 후 Fallback 제거</li>
 * </ol>
 *
 * @see com.gak.analyzer.optimization.ChatOptimizerConfig
 */
@Slf4j
public class RustChatOptimizer implements ChatOptimizer {

    /** 로드할 네이티브 라이브러리 이름 (OS별 접두사/확장자는 JVM이 자동 처리) */
    private static final String NATIVE_LIB_NAME = "gak_optimizer";

    private static final boolean NATIVE_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary(NATIVE_LIB_NAME);
            loaded = true;
            log.info("[RustChatOptimizer] Native library '{}' loaded successfully.", NATIVE_LIB_NAME);
        } catch (UnsatisfiedLinkError e) {
            log.warn(
                    "[RustChatOptimizer] Native library '{}' not found. Will use JavaChatOptimizer as fallback. Reason: {}",
                    NATIVE_LIB_NAME, e.getMessage());
        }
        NATIVE_LOADED = loaded;
    }

    /** 네이티브 라이브러리 사용 불가 시 위임할 Fallback 구현체 */
    private final ChatOptimizer fallback;

    public RustChatOptimizer(ChatOptimizer fallback) {
        this.fallback = fallback;
    }

    /**
     * JNI 네이티브 메서드 선언.
     *
     * <p>
     * Rust 측 구현 시그니처:
     * 
     * <pre>
     * #[no_mangle]
     * pub extern "system" fn Java_com_gak_analyzer_optimization_jni_RustChatOptimizer_optimizeNative(
     *     env: JNIEnv, _class: JClass, json_input: JString
     * ) -> jstring { ... }
     * </pre>
     *
     * @param jsonInput JSON 직렬화된 {@code List<RawChatMessage>}
     * @return JSON 직렬화된 {@link OptimizedBatch}
     */
    private native String optimizeNative(String jsonInput);

    @Override
    public OptimizedBatch optimize(List<RawChatMessage> messages) {
        if (!NATIVE_LOADED) {
            log.debug("[RustChatOptimizer] Native unavailable → delegating to fallback.");
            return fallback.optimize(messages);
        }

        // TODO: Rust 모듈 완성 후 아래 구현
        // 1. ObjectMapper로 messages → JSON 직렬화
        // 2. optimizeNative(json) 호출
        // 3. 응답 JSON → OptimizedBatch 역직렬화
        log.warn("[RustChatOptimizer] Native method not yet implemented → delegating to fallback.");
        return fallback.optimize(messages);
    }
}
