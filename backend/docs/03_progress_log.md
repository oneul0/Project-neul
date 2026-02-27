# Progress Log (Sprint 일지)
프로젝트 진행 상황 및 다음 단계(Next Steps)를 추적합니다.

---

## [2026-02-27] 백엔드 마이크로서비스 3종 스캐폴딩 완료

### 1. 완료된 작업

| 구분 | 내용 |
|------|------|
| **로컬 인프라** | `docker-compose.yml`으로 PostgreSQL(5432), Redis(6379), Kafka & Zookeeper(9092) 구성 완료 |
| **`neul-chat-collector`** | `[POST] /api/v1/broadcasts` API 구현, 스케줄러로 1초당 10개씩 더미 채팅을 `raw-chat-topic`으로 전송 |
| **`neul-analyzer`** | WebFlux + `@KafkaListener` 배치 마이크로배칭 구현, `@CircuitBreaker` (Resilience4j) Fallback 적용, `analyzed-chat-topic`으로 재생산 |
| **`neul-core-api`** | Kafka Consumer → R2DBC 비동기 PostgreSQL 저장, Redis Hash 실시간 통계 누적, `[GET] /api/v1/stream/{roomId}` SSE 브로드캐스터 구현 완료 |
| **공통 정비** | `ApiResponse<T>`, 스키마 DDL(`schema.sql`), `AnalyzedChat` Entity 작성 |
| **의존성 보강** | 모든 모듈 `build.gradle` 검토 및 Jackson, Kafka 의존성 추가, `application.yaml` Kafka 포트 통일(`9092`) |
| **문서화** | `docs/01_ADR.md`, `docs/02_troubleshooting.md`, `docs/04_run_guide.md`, `api-set.md` 작성 |
| **`.gitignore` 단일화** | 서브모듈에 흩어져 있던 파일들을 삭제하고 루트 폴더 하나로 통합 |

### 2. 주요 구현 포인트
- **Kafka 파티션 키:** 두 토픽 모두 `roomId`를 Key로 사용하여 동일 방의 채팅 메시지 순서 보장.
- **Reactive Stack 관철:** `WebFlux` + `R2DBC` + `ReactiveRedisTemplate` 전 구간 논블로킹.
- **Redis 통계 집계:** `room:{roomId}:stats` Hash에 `HINCRBY`로 O(1) 실시간 감정 통계 제공.
- **SSE Keep-Alive:** 15초마다 `ping` 이벤트를 내려 브라우저 연결 타임아웃 방지.

---

## [2026-02-27] 로컬 최초 실행 검증 및 런타임 버그 수정

### 1. 완료된 작업

| 구분 | 내용 |
|------|------|
| **gradlew.bat 생성** | `gradle` CLI 없이 `gradlew.bat` 수동 생성 (JDK 17 + Gradle 9.3.1 확인) |
| **reactor-kafka 제거** | Spring Boot 4.x의 `kafka-clients 4.x`와 생성자 시그니처 불일치 → `reactor-kafka` 완전 제거, `spring-kafka` 배치 `@KafkaListener`(`MAX_POLL_RECORDS=50`)로 교체 |
| **ReactiveRedisConnectionFactory 충돌 해결** | `RedisConfig`에서 `LettuceConnectionFactory` 직접 선언 제거, Spring Boot AutoConfiguration에 위임 |
| **ObjectMapper 빈 누락 해결** | `WebClientConfig`에 `@Bean ObjectMapper` + `JavaTimeModule` 명시적 등록 |
| **SSE 데이터 이벤트 수신 해결** | `Sinks.many().multicast()` → `replay(100)` 교체, `JsonSerializer.ADD_TYPE_INFO_HEADERS=false` 설정 |
| **KafkaConsumerConfig deprecated 코드 정리** | `JsonDeserializer` 생성자 방식 → Props 기반 + `ErrorHandlingDeserializer` 래핑 |
| **전체 파이프라인 동작 검증** | `collector → analyzer → core-api → SSE` 엔드투엔드 실시간 스트리밍 확인 완료 |

### 2. 검증된 SSE 출력 예시
```
event:chat_analyzed
data:{"messageId":"23ebf271-...","roomId":"test-room-1","content":"오늘따라 화질이 안 좋네요ㅠㅠ","emotion":{"type":"NEUTRAL","score":0.11},"analyzedAt":"2026-02-27T22:49:54"}

event:stats_update
data:{"POSITIVE":"14","TOTAL_COUNT":"20","NEGATIVE":"2","NEUTRAL":"4"}
```

### 3. 해소된 블로커

| 블로커 | 해결 방법 |
|--------|-----------|
| ~~`gradlew` 파일 미생성~~ | `gradlew.bat` 수동 생성 완료, Git 커밋에 포함 권장 |
| ~~`reactor-kafka` 버전 불일치~~ | 제거 후 spring-kafka 배치 방식으로 교체 |

### 4. 잔존 블로커 (Blockers)
- [ ] `GeminiAnalyzerService.simulateEmotion()` → 실제 Vertex AI WebClient 로직으로 교체 필요.
- [ ] `neul-chat-collector`의 더미 스케줄러 → 유튜브/치지직 실제 채팅 웹소켓 수집 로직으로 교체 필요.
- [ ] `DummyChatGenerator` roomId 하드코딩(`test-room-1`) → `POST /api/v1/broadcasts` API 응답 UUID와 연동 필요.
- [ ] 모의 데이터 스케줄러 종료 조건이 수동 API 호출에 의존. 예외 상황 시 메모리 릭 우려.

### 5. 다음 단계 (Next Steps)
1. **Gemini API 실제 WebClient 연동** — `analyzeBatch`의 임시 로직을 교체.
2. **유튜브 / 치지직 실시간 채팅 수집기** 연동.
3. **프론트엔드 연동** 전 CORS 설정 추가 및 Swagger API 문서 자동 생성 도구 적용.
