# Architecture Decision Records (ADR)
이 문서는 늘(Neul) 프로젝트를 진행하며 결정된 주요 아키텍처 및 기술 스택 도입 배경을 기록합니다.

---

## [ADR-001] 실시간 메시지 브로커로 Apache Kafka 도입
- **날짜:** 2026-02-27
- **결정 안건:** 수많은 실시간 채팅 데이터를 각 모듈로 전달하기 위한 메시지 브로커 선택
- **배경 및 문제 상황:** 유튜브/치지직 등에서 발생하는 대량의 실시간 채팅(초당 수백~수천 건 예상)을 수집(`collector`)하고, AI 분석(`analyzer`)을 거쳐 저장 및 푸시(`core-api`)해야 함. 각 서버 간 결합도를 낮추고 데이터 유실을 방지할 버퍼가 필요함.
- **고려한 대안들:**
  1. RabbitMQ
  2. Redis Pub/Sub
  3. Apache Kafka
- **최종 결정 및 에이전트의 생각:** **Apache Kafka** 대용량 실시간 스트리밍 처리에 가장 적합하며, 파티션(Partition)을 `roomId`로 지정함으로써 특정 방송 방의 채팅 순서를 완벽히 보장할 수 있기 때문에 선택. `raw-chat-topic`과 `analyzed-chat-topic` 두 개의 토픽을 생성하여 파이프라인을 분리함.

---

## [ADR-002] 늘(Neul) 분석 모듈 비동기/논블로킹 스택 (WebFlux) 도입
- **날짜:** 2026-02-27
- **결정 안건:** AI 분석 서버(`neul-analyzer`)의 프레임워크 스택 결정
- **배경 및 문제 상황:** Gemini API 등 외부 LLM 호출은 기본적으로 수십~수백 밀리초의 Network I/O를 발생시킴. 기존 Spring MVC(Tomcat) 모델에서는 스레드 풀 고갈 현상이 발생하여, 대규모 유입 채팅을 처리할 수 없음.
- **고려한 대안들:**
  1. Spring MVC + 코루틴/가상 스레드 (Java 21)
  2. Spring WebFlux (Reactor)
- **최종 결정 및 에이전트의 생각:** **Spring WebFlux**를 사용. 기존 Reactive 생태계와의 호환성이 좋으며, `bufferTimeout`을 활용하여 1초 또는 50건 단위의 Micro-batching을 우아하게(elegantly) 지원할 수 있어 선택함.
- **2026-02-27 업데이트:** 초기에는 `reactor-kafka`(Reactive Kafka 래퍼)를 사용했으나, Spring Boot 4.x의 `kafka-clients 4.x`와의 생성자 시그니처 불일치(`NoSuchMethodError: ConsumerRecord.<init>`) 문제로 **`reactor-kafka`를 제거**하고 `spring-kafka`의 **배치 `@KafkaListener`(`MAX_POLL_RECORDS=50`)** 방식으로 교체함. 감정 분석 로직 내부는 여전히 `Mono`/`Flux`를 활용하는 Reactive 방식 유지.

---

## [ADR-003] 외부 API 장애 격리를 위한 Resilience4j(Circuit Breaker) 적용
- **날짜:** 2026-02-27
- **결정 안건:** Vertex AI Gemini API 호출부 장애 전파 방지
- **배경 및 문제 상황:** 외부 AI API에 장애나 타임아웃이 발생할 경우, Analyzer 모듈 전체의 스레드가 블로킹되거나 무한 재시도하여 카프카 랙(Lag)이 급증할 위험이 있음.
- **고려한 대안들:**
  1. 단순 Try-Catch 및 Timeout 설정
  2. Resilience4j Circuit Breaker
- **최종 결정 및 에이전트의 생각:** **Resilience4j Circuit Breaker** 도입 결정. 예외 비율이 임계치를 넘으면 서킷을 열어 즉시 Fallback 메서드(`NEUTRAL` 감정으로 임시 응답)를 실행하게 하여 스트림 처리가 중단되지 않도록 조치함.

---

## [ADR-004] DB 저장 및 실시간 통계 조회 분리 방안 (R2DBC + Redis)
- **날짜:** 2026-02-27
- **결정 안건:** 채팅 로그 영구 저장 및 실시간 감정 지표 누적 계산
- **배경 및 문제 상황:** DB 단일 구성으로는 데이터 삽입(Insert)과 잦은 화면 통계 조회(Select Count)를 동시에 처리할 시 병목 발생. 성능을 챙기면서 논블로킹 패러다임을 유지해야 함.
- **고려한 대안들:**
  1. PostgreSQL만 사용하고 캐싱 적용
  2. PostgreSQL (영속 저장) + Redis Hash (실시간 누적 집계)
- **최종 결정 및 에이전트의 생각:** **PostgreSQL(R2DBC) + Redis Reactive** 조합 사용. 모든 로그는 R2DBC를 통해 비동기로 Postgres에 저장하고, 감정별 통계는 Redis의 **Hash 구조 (`room:{roomId}:stats`)**의 필드(POSITIVE, NEGATIVE 등)를 `INCRBy` 하여 O(1) 성능으로 즉시 제공하도록 책임을 분리함.
