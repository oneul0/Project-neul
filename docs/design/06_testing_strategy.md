이 프로젝트의 마이크로서비스 아키텍처(Kafka, Redis, PostgreSQL, SSE)를 검증하기 위한 엔드투엔드(E2E) 테스트 자동화 전략을 정의합니다.

> [!NOTE]
> 실제 테스트 실행 및 실습 방법은 [03_run_guide.md](03_run_guide.md)를 참고하세요.

## 1. 테스트 목적
- 전체 데이터 흐름(Collector -> Kafka -> Analyzer -> Kafka -> Core API -> SSE/Frontend)의 정합성 검증.
- 개별 서비스 간 인터페이스 변경 시 발생할 수 있는 부작용(Side Effects) 조기 발견.
- 하이라이트 감지 로직 및 실시간 통계 업데이트의 정확성 확보.

## 2. 테스트 범위 및 시나리오

### 시나리오 A: 전체 데이터 파이프라인 흐름
1. **Mock Chzzk Server**에 가상 채팅 메시지 주입 (Netty 기반 Mock WebSocket 사용).
2. `Collector`가 이를 수집하여 Kafka(`raw-chat-batch-topic`)로 전송하는지 확인.
3. `Analyzer`가 Kafka에서 읽어 감정을 분석 (Ollama API 모킹)하고 Kafka(`analyzed-chat-topic`)로 재전송하는지 확인.
4. `Core API`가 이를 DB(`AnalyzedChat`)에 저장하고 Redis 통계를 업데이트하는지 확인.
5. `Core API`의 SSE 엔드포인트(`GET /api/v1/channels/{id}/subscribe`)를 통해 최종 분석 데이터가 정해진 포맷으로 배출되는지 확인.

### 시나리오 B: 하이라이트 감지 검증
1. 특정 감정 스파이킹(예: JOY 점수 0.9)을 유도하는 가상 채팅 뭉치 주입.
2. `Core API`의 하이라이트 엔진이 Relative Spike를 감지하여 SSE(`highlight_detected` 이벤트) 및 DB(`HighlightRecord`)에 정확히 기록하는지 확인.

## 3. 세부 기술 구현 방안

### 3.1 백엔드 테스트 디렉토리 구조
```text
backend/core-api/src/test/java/com/gak/core_api/e2e/
├── E2ETestBase.java            # Testcontainers (Kafka, Redis, PG) 추상 베이스
├── FullPipelineE2ETest.java    # 시나리오 A 검증
└── HighlightE2ETest.java       # 시나리오 B 검증

backend/collector/src/test/java/com/gak/collector/mock/
└── MockChzzkServer.java        # WebSocket 핸드쉐이크 및 데이터 송출 시뮬레이터
```

### 3.2 핵심 검증 로직 (Sample)
- **StepVerifier (Project Reactor)**: SSE 스트림의 개별 이벤트를 검증할 때 사용.
  ```java
  webTestClient.get().uri("/api/v1/channels/test-room/subscribe")
      .accept(MediaType.TEXT_EVENT_STREAM)
      .exchange()
      .returnResult(Map.class)
      .getResponseBody()
      .as(StepVerifier::create)
      .expectNextMatches(msg -> msg.get("event").equals("chat_analyzed"))
      .thenCancel()
      .verify();
  ```

### 3.3 인프라 모킹 전략
- **Chzzk Mock**: `collector` 모듈에서 호출하는 실시간 상태 API(`live-status`)와 WebSocket을 `WireMock` 및 `MockServer`로 대체하여 실제 외부 네트워크 의존성 제거.
- **Ollama Mock**: `analyzer` 모듈의 `OllamaAnalyzerService`를 Mock 스터브로 교체하거나, `WireMock`을 통해 HTTP 응답 JSON만 시뮬레이션.

## 4. 프론트엔드 E2E (Playwright) 구체화

### 4.1 테스트 환경 준비
- `TEST_MODE=true` 환경변수로 백엔드 구동 시 Kafka/Redis/DB를 인메모리 또는 로컬 도커로 연결.
- Playwright 테스트 코드에서 `page.goto('/channels/test-channel')` 호출 전, 테스트 데이터 주입 API(Internal 전용) 호출.

### 4.2 UI 검증 포인트
- **SSE 수신 여부**: 네트워크 탭의 EventSource 연결 상태 확인.
- **Recharts 정합성**: 차트 내부의 SVG `path`나 `circle` 요소의 좌표 변화 감지.
- **하이라이트 목록**: 하이라이트 감지 시 사이드바에 카드 컴포넌트 추가 여부 확인.

### 시나리오 C: 통계 집계 정합성
1. 여러 감정(JOY, ANGER 등)의 메시지를 대량 주입.
2. Redis에서 관리되는 실시간 통계 값(TOTAL_COUNT, 감정별 카운트)이 주입한 수와 일치하는지 확인.

## 3. 기술 스택 및 도구

| 구분 | 도구 | 용도 |
|------|------|------|
| **인프라 제어** | **Testcontainers** | Kafka, Redis, PostgreSQL을 테스트 환경에서 동적으로 띄우고 제거 |
| **API/소켓 모킹** | **MockServer / WireMock** | Chzzk API 및 WebSocket 서버 모킹 |
| **백엔드 검증** | **WebTestClient / RestAssured** | API 엔드포인트 및 SSE 스트림 수신 데이터 검증 |
| **프론트엔드 E2E** | **Playwright** | 실제 브라우저 환경에서 대시보드 차트 및 하이라이트 UI 업데이트 확인 |
| **리포팅** | **Allure / JUnit Report** | 테스트 결과 가시화 및 히스토리 관리 |

## 4. 단계별 구현 계획

### Phase 1: 백엔드 통합 테스트 (Core Pipeline)
- `Testcontainers` 기반의 `Analyzer` + `Core API` 통합 테스트 환경 구축.
- Kafka 프로듀서/컨슈머 연동 테스트 코드 작성.

### Phase 2: 외부 연동 모킹 (Collector Test)
- Chzzk WebSocket 서버를 모킹하여 `Collector`의 수집 주기를 테스트.
- 네트워크 지연(Latency) 상황 시나리오 추가.

### Phase 3: 브라우저 기반 UI 테스트 (Frontend E2E)
- Playwright를 사용하여 SSE 이벤트 수신 시 Recharts 차트가 실제로 갱신되는지 시각적 회귀(Visual Regression) 테스트 포함.

## 5. 실행 및 자동화 (CI/CD)
- GitHub Actions 워크플로우에 통합.
- 각 서비스의 `Pull Request` 생성 시 전체 E2E 테스트 자동 실행.
- 중대한 실패 발생 시 알림 시스템(Slack/Discord) 연동.

---

## 6. 현재 테스트 현황 (2026-05-18 기준)

### 6-1. 단위 테스트 — 전체 통과

| 테스트 클래스 | 모듈 | 테스트 수 | 결과 | 검증 대상 |
|---|---|---|---|---|
| `OwnerIdentityResolverTest` | core-api | 9 | ✅ 전체 통과 | IDOR 방어, 쿠키 인증, 필터 캐시 |
| `OllamaAnalyzerServiceTest` | analyzer | 9 | ✅ 전체 통과 | LLM 배치 분석, 하이라이트 판정, 폴백 |
| `VodHighlightAnalyzerTest` | analyzer | 4 | ✅ 전체 통과 | 하이라이트 스코어링, LLM 리뷰 흐름 |
| `JavaChatOptimizerTest` | analyzer | 9 | ✅ 전체 통과 | 채팅 압축·중복 제거 |
| `CollectorApplicationTests` | collector | 1 | ✅ 전체 통과 | 스프링 컨텍스트 로드 |

**총 32개 단위 테스트, 0개 실패**

### 6-2. E2E 테스트 — 인프라 필요로 스킵

Testcontainers(Kafka · Redis · PostgreSQL) 환경이 갖춰진 CI에서만 실행.
로컬에서는 `@Disabled` 또는 환경변수 조건으로 기본 제외된다.

| 테스트 클래스 | 테스트 수 | 상태 | 필요 인프라 |
|---|---|---|---|
| `VodFlowE2ETest` | 12 | SKIPPED | Kafka · Redis · PostgreSQL |
| `FullPipelineE2ETest` | 1 | SKIPPED | 동일 |
| `HighlightE2ETest` | 1 | SKIPPED | 동일 |
| `DatabaseConnectionTest` | 1 | SKIPPED | PostgreSQL |

### 6-3. 브라우저 기반 수동 검증 — 완료

Playwright 기반 수동 시나리오 결과는 [17_auth_reliability_test_scenarios.md](17_auth_reliability_test_scenarios.md) 참고.

| 시나리오 | 결과 |
|---|---|
| AUTH-001 ~ AUTH-004 (인증 신뢰성 핵심 4개) | 통과 |
| AUTH-005 (런타임 의존성) | 환경 제약으로 일부 제한 |
| AUTH-006 ~ AUTH-007 (real 8083 백엔드 포함) | 통과 |
