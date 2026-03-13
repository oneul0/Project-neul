# Testing & Documentation Guide

이 문서는 프로젝트의 성능 최적화(Java -> Rust 전환) 과정에서 수행해야 할 테스트와 문서화의 표준 가이드를 제공합니다.

## 1. 테스트 표준 (Testing Standards)

### 1-1. 기능적 동질성 테스트 (Functional Parity Test)
Java 구현체와 Rust 구현체가 동일한 입력에 대해 항상 동일한 출력을 내는지 확인합니다.
- **방법**: JUnit을 사용하여 `JavaImplementation`과 `RustImplementation`에 동일한 파라미터를 넣고 `assertEquals`로 결과 비교.
- **범위**: 정상 케이스, Null 처리, 특수 문자, 대용량 입력.

### 1-2. 성능 벤치마크 (Performance Benchmarking)
`PerformanceProfiler` 클래스를 사용하여 정량적 지표를 추출합니다.
- **예시**:
  ```java
  PerformanceProfiler.profile("Rust-Optimization", () -> nativeBridge.optimize(batch));
  ```
- **지표**: 실행 시간(ms), 가비지 컬렉션(GC) 발생 횟수, 메모리 점유율.

---

## 2. 문서화 가이드 (Documentation Guide)

### 2-1. 성능 전이 로그 (`performance_migration_log.md`)
최적화 세션마다 다음 내용을 기록합니다.
- **Target**: 최적화 대상 메서드/클래스.
- **Metric Table**: Java 초기값, Rust 결과값, 개선율(%).
- **Implementation Note**: 적용한 기술(SIMD, Zero-copy 등)과 특이사항.

### 2-2. 워크스루 업데이트 (`walkthrough.md`)
주요 아키텍처 변경이나 큰 기능 추가 시에만 요약하여 기록합니다.
- 무엇이 바뀌었는가?
- 왜 바뀌었는가?
- 최종 결과는 어떠한가?

---

## 3. 성능 측정 시 주의사항
1. **JVM Warm-up**: JIT 컴파일러가 최적화를 마칠 수 있도록 최소 10,000회 이상의 사전 실행(Warm-up) 후 측정하십시오.
2. **Side Effects**: DB I/O나 네트워크 지연이 포함되지 않도록 순수 알고리즘 영역만 격리하여 측정하십시오.
3. **Environment**: CPU 온도나 다른 백그라운드 프로세스의 간섭이 없는 안정적인 상태에서 측정하십시오.
