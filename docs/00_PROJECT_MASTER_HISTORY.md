# 늘(Neul) 프로젝트 마스터 히스토리 (Master History)

이 문서는 "늘(Neul)" 프로젝트의 시작부터 현재까지의 모든 개발 진행 상황, 이슈 해결, 아키텍처 결정을 일자별로 통합하여 기록합니다.

---

## 📅 2026-02-27: 프로젝트 스캐폴딩 및 기초 파이프라인 구축

### [완료된 작업]
- **백엔드 마이크로서비스 3종 스캐폴딩**: `collector`, `analyzer`, `core-api` 모듈 생성.
- **로컬 인프라 구성**: Docker Compose를 이용한 PostgreSQL, Redis, Kafka 환경 구축.
- **기초 파이프라인**: 
  - `collector`: 더미 채팅 생성 및 Kafka 전송.
  - `analyzer`: WebFlux 기반 마이크로배칭 및 감정 분석 시뮬레이션.
  - `core-api`: Kafka 소비, DB 저장, Redis 통계 집계 및 SSE 푸시.
- **ADR 수립**: ADR-001(Kafka), ADR-002(WebFlux), ADR-003(Resilience4j), ADR-004(R2DBC+Redis).

### [이슈 해결 (Troubleshooting)]
- **Gradle Wrapper**: `gradlew` 파일 미생성 문제 해결을 위해 수동 생성 및 배포.
- **Library Conflict**: `reactor-kafka`와 `kafka-clients 4.x` 버전 불일치 해결을 위해 `spring-kafka` 배치 리스너로 전환.
- **Redis Conflict**: AutoConfiguration 중복 빈 충돌 해결.
- **SSE Issue**: `multicast()`를 `replay()`로 변경하여 데이터 유실 방지.

---

## 📅 2026-03-04: 데이터 최적화 레이어 (Chat Optimizer) 설계

### [완료된 작업]
- **Optimization Layer**: Gemini API 호출 비용 절감을 위한 필터링 및 압축 로직 구현.
- **JNI 준비**: 추후 Rust 모듈 교체를 위해 Port & Adapter 패턴 적용 (`ChatOptimizer` 인터페이스).
- **테스트**: `JavaChatOptimizer` 단위 테스트 9종 통과.

### [이슈 해결 (Troubleshooting)]
- **Gradle Test Engine**: JUnit 5 엔진 명시적 활성화 (`useJUnitPlatform()`).
- **Signature Mismatch**: `CompressedChat` 도입에 따른 서비스 시그니처 정비.

---

## 📅 2026-03-05 ~ 2026-03-06: 치지직(Chzzk) 연동 및 프론트엔드 시작

### [완료된 작업]
- **Chzzk API 연동**: Client Auth 기반 세션 발급 및 소켓(Socket.IO) 수집기 구현.
- **이벤트 라우팅**: 후원/구독 이벤트는 분석 없이 즉시 통과(Passthrough) 처리.
- **프론트엔드 스캐폴딩**: Next.js 16 + Tailwind CSS v4 환경 구성.
- **ADR 수립**: ADR-005 (Chzzk API 서버 통합 인증 도입).

---

## 📅 2026-03-09 ~ 2026-03-10: 런타임 안정화 및 인증 로직 재작성

### [완료된 작업]
- **안정화**: Redis 다운 시에도 Core API가 동작하도록 Fallback 로직 강화.
- **인증 리팩토링**: 치지직 공식 API 스펙(Server-to-Server)에 맞춰 OAuth Flow 대신 헤더 기반 Client Auth로 전면 수정.
- **UI 픽스**: 프론트엔드 API 매핑 에러(Optional Chaining) 수정.

---

## 📅 2026-03-14: 고성능 NID 웹소켓 도입 및 1분 배치 아키텍처 (Sprint 1)

### [완료된 작업]
- **Collection**: 공식 API의 한계를 넘기 위해 브라우저 내부 웹소켓(NID Chat) 직접 연동 성공.
- **Micro-batching**: 초당 수천 건 처리를 위해 1분 단위 `RawChatBatch` 전송 구조로 전환.
- **Common Module**: 데이터 일관성을 위해 모든 DTO를 `common` 모듈로 통합 및 전수 리팩토링.
- **JNI Foundation**: `NativeBridge` 구현 및 성능 측정을 위한 `PerformanceProfiler` 유틸리티 추가.
- **Documentation**: ADR-001~003(신규), 성능 전이 로그, JNI 가이드 등 문서 체계화.

### [이슈 해결 (Troubleshooting)]
- **Port Conflict**: 8082 포트 점유 프로세스 정리.
- **Import Hell**: `common` 모듈 통합 후 발생한 수백 개의 Import 오류 일괄 수정.

---

## 🚀 향후 로드맵 (Next Steps)
1. **Frontend UI**: 1분 배치 데이터 시각화 및 실시간 차트 고도화.
2. **Rust Native**: `NativeBridge`를 실제 Rust(.so/.dll) 모듈과 연결하여 성능 개선 수치 확보.
3. **Load Test**: 실제 대규모 채널 환경에서 1분 배칭 파이프라인의 안정성 검증.
