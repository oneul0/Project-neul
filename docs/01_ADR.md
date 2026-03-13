# Consolidated Architecture Decision Records (ADR)

이 문서는 프로젝트 "늘(Neul)"의 모든 아키텍처 결정 사항을 통합 관리합니다.

---

## [ADR-001] 실시간 메시지 브로커로 Apache Kafka 도입
- **날짜:** 2026-02-27
- **결정:** Apache Kafka 사용.
- **이유:** 대량 실시간 채팅 스트리밍 처리 및 방별 순서 보장(Partition Key: `roomId`)에 최적.

## [ADR-002] 비동기/논블로킹 스택 (WebFlux) 도입
- **날짜:** 2026-02-27
- **결정:** Spring WebFlux (Reactor).
- **이유:** 외부 LLM 호출(I/O) 대기 시간 동안 스레드 효율성 극대화 및 마이크로 배칭 지원.

## [ADR-003] 장애 격리를 위한 Resilience4j 적용
- **날짜:** 2026-02-27
- **결정:** Resilience4j Circuit Breaker.
- **이유:** 외부 AI API 장애 시 전체 파이프라인 및 SSE 전송이 중단되지 않도록 보호.

## [ADR-004] DB 저장 및 실시간 통계 분리 (R2DBC + Redis)
- **날짜:** 2026-02-27
- **결정:** PostgreSQL (영속 저장) + Redis Hash (실시간 집계).
- **이유:** 쓰기 부하와 읽기 부하를 분리하고, Redis Hash를 통해 O(1) 통계 조회 성능 확보.

## [ADR-005] Chzzk API 연동 시 클라이언트 인증 도입
- **날짜:** 2026-03-05
- **결정:** 서버 통합 인증(Client Auth) 및 이벤트 Passthrough.
- **이유:** 개별 사용자 토큰 관리 복잡성 제거 및 후원/구독 이벤트의 지연 없는 전송.

## [ADR-006] Direct NID Chat WebSocket Protocol 도입
- **날짜:** 2026-03-14
- **결정:** 공식 API 대신 브라우저 내부 WebSocket 프로토콜 직접 연동.
- **이유:** 일일 호출 제한(10만 건) 회피 및 대규모 실시간 데이터 수집 확장성 확보.

## [ADR-007] 1-Minute Reactive Micro-batching 도입
- **날짜:** 2026-03-14
- **결정:** 1분 단위 `window` 기반 배치 처리.
- **이유:** LLM 엔진 부하 경감, 가독성 높은 통계 제공, 향후 Rust 모듈과의 데이터 교환 효율성 증대.

## [ADR-008] DTO 통합 및 `common` 모듈화
- **날짜:** 2026-03-14
- **결정:** 모든 모듈의 DTO를 `backend/common`으로 통합.
- **이유:** 모듈 간 데이터 규약 일치 및 유지보수 효율성 증대.
