package com.neul.collector.jni;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Rust/JNI 연동을 위한 브릿지 클래스.
 * - 현재는 Java 구현체로 동작하지만, 향후 고성능 처리가 필요한 구간은 Rust로 대체됩니다.
 */
@Slf4j
@Component
public class NativeBridge {

    static {
        try {
            // System.loadLibrary("neul_native");
            log.info("[NativeBridge] neul_native library not found. Falling back to Java implementation.");
        } catch (UnsatisfiedLinkError e) {
            log.warn("[NativeBridge] Failed to load native library: {}", e.getMessage());
        }
    }

    /**
     * 예시: 수집된 채팅 메시지를 Rust 모듈에서 전처리/필터링할 때 사용.
     * @param rawContent 원본 JSON 스트링
     * @return 필터링되거나 변환된 데이터
     */
    public String preprocessChat(String rawContent) {
        // TODO: native 구현체 연결 시 아래 메서드로 대체
        // return native_preprocessChat(rawContent);
        return rawContent; 
    }

    // private native String native_preprocessChat(String rawContent);
}
