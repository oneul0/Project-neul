package com.gak.common.util;

import lombok.extern.slf4j.Slf4j;
import java.util.function.Supplier;

/**
 * 성능 측정을 위한 유틸리티 클래스.
 * Java 구현과 Rust(JNI) 구현의 실행 시간을 비교하고 기록할 때 사용합니다.
 */
@Slf4j
public class PerformanceProfiler {

    /**
     * 특정 작업의 실행 시간을 측정하고 결과를 로그로 출력합니다.
     *
     * @param taskName 작업 이름
     * @param task     실행할 작업 (리턴값 없음)
     */
    public static void profile(String taskName, Runnable task) {
        long start = System.nanoTime();
        try {
            task.run();
        } finally {
            long end = System.nanoTime();
            log.info("[Profiler] {} executed in {} ms", taskName, (end - start) / 1_000_000.0);
        }
    }

    /**
     * 특정 작업의 실행 시간을 측정하고 리턴값을 반환합니다.
     *
     * @param taskName 작업 이름
     * @param task     실행할 작업 (리턴값 있음)
     * @param <T>      리턴 타입
     * @return 작업 결과
     */
    public static <T> T profile(String taskName, Supplier<T> task) {
        long start = System.nanoTime();
        T result;
        try {
            result = task.get();
        } finally {
            long end = System.nanoTime();
            log.info("[Profiler] {} executed in {} ms", taskName, (end - start) / 1_000_000.0);
        }
        return result;
    }
}
