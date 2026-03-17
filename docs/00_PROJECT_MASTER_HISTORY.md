# 늘(Neul) 프로젝트 마스터 히스토리 (Master History)

이 문서는 "늘(Neul)" 프로젝트의 시작부터 현재까지의 모든 개발 진행 상황, 이슈 해결, 아키텍처 결정을 일자별로 통합하여 기록합니다.

---

## 📂 프로젝트 문서 색인 (Documentation Index)

| 번호 | 문서명 | 주요 내용 |
|:---:|---|---|
| 00 | [마스터 히스토리](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/00_PROJECT_MASTER_HISTORY.md) | 프로젝트 전체 진행 현황 및 타임라인 |
| 01 | [ADR (의사결정 기록)](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/01_ADR.md) | 주요 설계 결정 사항 (Kafka, WebFlux, JNI 등) |
| 02 | [트러블슈팅 로그](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/02_troubleshooting_log.md) | 장애 상황 및 버그 해결 기록 |
| 03 | [로컬 실행 가이드](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/03_run_guide.md) | 환경 설정, 서비스 실행 및 E2E 테스트 방법 |
| 04 | [기술 개념 가이드](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/04_technical_concepts.md) | 사용된 기술 스택(Reactor, Redis 등) 심층 분석 |
| 05 | [개발자 인수인계서](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/05_developer_handover.md) | 신규 개발자를 위한 온보딩 및 구조 가이드 |
| 06 | [테스트 자동화 전략](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/06_testing_strategy.md) | E2E 테스트 시나리오 및 검증 전략 |
| 07 | [네이티브 최적화 가이드](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/07_native_optimization_guide.md) | Java → Rust 전환/최적화 표준 가이드 |
| 08 | [성능 전이 로그](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/08_performance_migration_log.md) | 최적화 세션별 성능 개선 데이터 기록 |

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
- **실시간 채팅 수집기**: `NidChatCollector` 구축을 통해 `wss://kr-ss1.chat.naver.com/chat` 실시간 데이터 수집 시작.
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
- **Micro-batching**: 초당 수천 건 처리를 위해 기존 1분 단위를 **2초 단위(`window(Duration.ofSeconds(2))`)** 고속 마이크로배칭으로 고도화.
- **Emotion Analysis**: Ollama를 통한 **7가지 감정 모델**(JOY, HOPE, NEUTRAL, SADNESS, ANGER, WONDER, DISGUST) 분석 기능 추가.
- **Highlight Engine**: 0.8 이상의 감정 스파이크를 실시간 감지하여 하이라이트 이벤트 발행 기능 구현.
- **Common Module**: 데이터 일관성을 위해 모든 DTO를 `common` 모듈로 통합 및 전수 리팩토링.
- **JNI Foundation**: `NativeBridge` 구현 및 성능 측정을 위한 `PerformanceProfiler` 유틸리티 추가.
- **Documentation**: ADR-001~003(신규), 성능 전이 로그, JNI 가이드 등 문서 체계화.

### [이슈 해결 (Troubleshooting)]
- **Port Conflict**: 8082 포트 점유 프로세스 정리.
- **Import Hell**: `common` 모듈 통합 후 발생한 수백 개의 Import 오류 일괄 수정.

---

## 📅 2026-03-17: 데이터 처리 기술 정리 및 E2E 학습 가이드 통합

### [완료된 작업]
- **Technical Summary**: 대용량 데이터 대응을 위한 5대 핵심 전략(리액티브, 카프카 배치, 최적화 엔진, 패스스루, Redis 집계) 정리.
- **Run Guide Integration**: E2E 테스트 실습 가이드를 [03_run_guide.md](file:///c:/Users/Oneul/Desktop/Projects/Project-neul/docs/03_run_guide.md) 내 '섹션 5'로 통합.

---

## 🚀 향후 로드맵 (Next Steps)
1. **Frontend UI**: 1분 배치 데이터 시각화 및 실시간 차트 고도화.
2. **Rust Native**: `NativeBridge`를 실제 Rust(.so/.dll) 모듈과 연결하여 성능 개선 수치 확보.
3. **Load Test**: 실제 대규모 채널 환경에서 1분 배칭 파이프라인의 안정성 검증.
