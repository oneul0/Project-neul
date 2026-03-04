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
- [x] ~~`GeminiAnalyzerService.simulateEmotion()` 시그니처~~ → `CompressedChat` 기반으로 정비 완료 (Gemini 연동은 별도 작업).
- [ ] `neul-chat-collector`의 더미 스케줄러 → 유튜브/치지직 실제 채팅 웹소켓 수집 로직으로 교체 필요.
- [ ] `DummyChatGenerator` roomId 하드코딩(`test-room-1`) → `POST /api/v1/broadcasts` API 응답 UUID와 연동 필요.
- [ ] 모의 데이터 스케줄러 종료 조건이 수동 API 호출에 의존. 예외 상황 시 메모리 릭 우려.

### 5. 다음 단계 (Next Steps)
1. **Gemini API 실제 WebClient 연동** — `analyzeBatch`의 임시 로직을 교체.
2. **유튜브 / 치지직 실시간 채팅 수집기** 연동.
3. **프론트엔드 연동** 전 CORS 설정 추가 및 Swagger API 문서 자동 생성 도구 적용.

---

## [2026-03-04] Chat Data Optimizer 설계 및 구현

### 1. 배경 및 목표
Gemini API 연동 전, **API 호출 비용(토큰 수)과 JVM 연산 비용**을 줄이기 위한 채팅 데이터 최적화 레이어 구축. 핵심 요구사항은 현재 Java 구현체를 작성하되, **추후 Rust 네이티브 모듈을 JNI로 교체**할 수 있는 구조를 선제적으로 설계하는 것.

---

### 2. 아키텍처 의사결정 (ADR-005)

4가지 패턴을 정량 비교 후 **Port & Adapter 패턴** 채택. (`docs/architecture_decision_records.md` 참조)

| 패턴 | JNI 의미 표현 | 교체 용이성 | 채택 |
|------|---|---|------|
| Strategy | ⚠️ 알고리즘 변형으로만 표현 | ✅ | ❌ |
| Template Method | ❌ 상속 기반, 계층 전체 수정 | ❌ | ❌ |
| Chain of Responsibility | ❌ 엔진 전체 교체 개념 불일치 | ❌ | ❌ |
| **Port & Adapter** | ✅ JNI = 외부 어댑터로 명확 표현 | ✅ | ✅ |

> **핵심 결정 이유**: Rust/JNI는 단순한 "알고리즘 변형"이 아니라 Java 런타임 외부 경계(External Native Boundary)를 넘는 행위다. Port & Adapter가 이 의도를 코드 구조 자체로 명시한다.

---

### 3. 완료된 작업

| 구분 | 내용 |
|------|------|
| **신규 파일 (6개)** | `ChatOptimizer` (포트), `CompressedChat`, `OptimizedBatch`, `ChatOptimizerConfig`, `JavaChatOptimizer`, `RustChatOptimizer` |
| **수정 파일 (3개)** | `ChatAnalysisProcessor`, `GeminiAnalyzerService`, `application.yaml` |
| **테스트** | `JavaChatOptimizerTest` — 9개 단위 테스트 (Spring Context 없이 순수 JUnit 5) |
| **ADR 문서** | `docs/architecture_decision_records.md` (ADR-005) 신규 작성 |
| **빌드 설정** | 루트 `build.gradle`에 `useJUnitPlatform()` 추가 |

#### 최적화 파이프라인 (2단계)

**Step A — Filter (스팸 제거)**
```
Rule 1: content 길이 < 2자 → 드롭
Rule 2: 이모지·특수기호만으로 구성 → 드롭 (정규식: [\p{So}\p{Sk}\p{Sm}\p{Sc}\p{Zs}\s]+)
Rule 3: 동일 sender + 동일 content 배치 내 도배 → 첫 번째만 유지
```

**Step B — Compress (중복 압축)**
```
정규화된 content 기준 그룹핑 (trim + toLowerCase)
→ 그룹별 대표 메시지 1건 + count 필드로 CompressedChat 생성
→ Gemini 프롬프트 전달 시 "내용 (N건)" 형태로 활용 예정
```

**전환 스위치**: `application.yaml`에서 값 하나만 변경
```yaml
app.optimizer.engine: rust   # java → rust (Rust 모듈 완성 후 전환)
```

---

### 4. 트러블슈팅

#### 🔴 Issue 1: Gradle 테스트 엔진 미설정
- **증상**: `No tests found for given includes` — Gradle이 JUnit 5 테스트를 발견하지 못함.
- **원인**: Gradle 9.x에서는 `spring-boot-starter-test`가 JUnit 5 의존성을 포함해도, `test { useJUnitPlatform() }` 명시 없이는 테스트 엔진을 자동 활성화하지 않음.
- **해결**: 루트 `build.gradle`의 `subprojects` 블록에 `test { useJUnitPlatform() }` 추가.
- **영향 범위**: `collector`, `analyzer`, `core-api` 모든 모듈 테스트 환경 정상화.

```groovy
// build.gradle (root) — 추가된 내용
subprojects {
    // ... 기존 설정 ...
    test {
        useJUnitPlatform()  // ← 이 한 줄로 해결
    }
}
```

#### 🟡 Issue 2: GeminiAnalyzerService 시그니처 불일치
- **증상**: `analyzeBatch(List<RawChatMessage>)` → `List<CompressedChat>` 전달 시 컴파일 에러.
- **원인**: `ChatAnalysisProcessor`를 먼저 수정하고 `GeminiAnalyzerService`를 나중에 수정하는 순서로 작업해 일시적 불일치 발생.
- **해결**: `GeminiAnalyzerService.analyzeBatch()` 파라미터를 `List<CompressedChat>`으로 변경, `simulateEmotion()`에서 `representativeId`를 `messageId`로 매핑.

---

### 5. 단위 테스트 결과

```
JavaChatOptimizerTest — tests=9, failures=0, errors=0 (0.124s)

✅ 1자 이하 메시지는 필터링된다
✅ 이모지만으로 구성된 메시지는 필터링된다
✅ 같은 sender의 동일 내용 도배는 첫 번째만 남긴다
✅ 다른 sender의 메시지는 내용이 같아도 필터링되지 않는다
✅ 동일한 내용의 메시지는 1개로 압축되며 count가 정확하다
✅ 고유한 메시지는 각각 별도의 CompressedChat으로 유지된다
✅ 대소문자/공백 차이는 동일 그룹으로 처리된다
✅ 압축률이 정확하게 계산된다 (10건 → 2건 = 80%)
✅ 빈 배치 입력 시 빈 결과와 0% 압축률을 반환한다
```

실측 압축률 예시 (로그):
```
[Optimizer] original=50, filtered=8, compressed=15, reduction=70.0%
[Optimizer] original=10, filtered=0, compressed=2, reduction=80.0%
```

---

### 6. 잔존 블로커 (Blockers)
- [ ] `GeminiAnalyzerService.simulateEmotion()` → 실제 Vertex AI WebClient 로직으로 교체 필요 (다음 주요 작업).
- [ ] `RustChatOptimizer.optimizeNative()` → Rust 모듈 개발 후 JSON 직렬화/역직렬화 구현 필요.
- [ ] `neul-chat-collector` 더미 스케줄러 → 실제 chat WebSocket 수집 로직 교체 필요.

### 7. 다음 단계 (Next Steps)
1. **Gemini API WebClient 실제 연동** — `ChatAnalysisProcessor` → `CompressedChat` → Gemini 프롬프트 구성 → 응답 파싱.
2. **유튜브 / 치지직 채팅 수집기** 연동.
3. **프론트엔드 연동** 전 CORS 설정 및 API 문서 작성.
