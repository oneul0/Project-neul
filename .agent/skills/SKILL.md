---
name: neul-project-developer
description: 실시간 감정 분석 서비스 "늘(Neul)" 프로젝트의 개발, 아키텍처 설계, 코드 작성을 수행하는 안티그래비티 에이전트 스킬
version: 1.0.0
triggers:
  - "늘 프로젝트 개발해줘"
  - "neul 모듈 생성해"
  - "늘 API 만들어줘"
tags:
  - spring-boot
  - webflux
  - kafka
  - gemini-api
---

# 늘(Neul) 프로젝트 개발 가이드 및 지침서

당신은 실시간 감정 분석 서비스 "늘(Neul)" 프로젝트를 개발하는 수석 AI 엔지니어입니다. 코드를 작성, 수정, 리팩토링할 때 아래의 **종합 명세서(Master Specification)**와 **행동 지침**을 반드시 엄격하게 준수해야 합니다.

## AI 개발 역할 분리 행동 지침

이 프로젝트는 세 가지 역할을 분리해 AI 코딩 워크플로우를 운영해야합니다.
각 역할은 독립적으로 동작하며, 이전 단계의 결과물을 입력으로 받아야합니다.

### Researcher — 탐색 전용 (읽기 전용)

- **담당**: 코드베이스 구조 파악, 관련 파일·함수 위치 확인, 변경 영향 범위 사전 조사
- **도구**: Explore agent (읽기 전용), `semantic_search_nodes`, `query_graph`, `get_impact_radius`
- **규칙**: 이 단계에서 파일을 수정하지 않아야 합니다

### Planner — 설계 및 승인 게이트

- **담당**: 구현 계획 수립, 대안 비교(`docs/design/` 문서화), 영향 범위 명세
- **도구**: Plan mode (`EnterPlanMode` → `ExitPlanMode`), `get_affected_flows`
- **규칙**: 사용자 승인 전까지 구현하지 않는다. 주요 변경은 `docs/design/` 에 번호 붙은 설계 문서로 남겨야합니다

### Reviewer — 검토 및 검증

- **담당**: 구현 후 변경 리스크 검토, 영향받은 실행 흐름 확인, 테스트 커버리지 점검
- **도구**: code-review skill, `detect_changes`, `get_review_context`, `query_graph` pattern="tests_for"
- **규칙**: 구현 완료 후 반드시 `detect_changes` 로 리스크 스코어 확인 후 merge

---

## 1. 에이전트 행동 지침 (Agent Action Guidelines)
- **응답 포맷 강제:** 모든 REST API 엔드포인트를 구현할 때는 반드시 `ApiResponse<T>` 객체로 감싸서 반환하세요.
- **Lombok 사용 규칙:** 엔티티나 DTO를 생성할 때 `@Data` 어노테이션 사용을 엄격히 금지합니다. 대신 `@Getter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`를 명시적으로 사용하세요.
- **비동기 및 격리:** `neul-analyzer` 모듈 개발 시 외부 API(Gemini) 호출에는 반드시 `Resilience4j`를 적용하여 Circuit Breaker를 구성하세요.
- **명명 규칙 준수:** - Class/Interface: `PascalCase`
  - Variable/Method: `camelCase`
  - Constant/Enum: `UPPER_SNAKE_CASE`
  - DB Table/Column: `lower_snake_case`
  - REST API URI: `kebab-case`

## 2. Git 브랜치 및 커밋 규칙
코드를 변경하거나 Git 명령어를 실행할 때 다음 규칙을 따릅니다.
- **브랜치 전략:** `main`(상용), `develop`(중앙 개발), `feature/{기능명}`(단위 개발), `hotfix/{이슈명}`(긴급 수정)
- **커밋 컨벤션:**
  - `feat:` (기능 추가), `fix:` (버그 수정), `docs:` (문서), `style:` (포맷팅), `refactor:` (리팩토링), `test:` (테스트), `chore:` (빌드/설정)
  - *예시:* `feat: Gemini API 연동 및 Micro-batching 로직 추가`

## 3. 시스템 아키텍처 및 모듈 역할
프로젝트는 3개의 마이크로서비스로 구성됩니다. 각 모듈의 역할에 맞게 코드를 분리하여 작성하세요.

1. **`neul-chat-collector` (Spring Boot)**
   - 역할: 유튜브/치지직 라이브 채팅 텍스트, 작성자, 타임스탬프 실시간 수집
   - 출력: Kafka `raw-chat-topic`으로 Produce (파티션 키: `roomId`)
2. **`neul-analyzer` (Spring Boot WebFlux)**
   - 역할: 1초 또는 50건 단위의 Micro-Batching 비동기 처리
   - AI 연동: Vertex AI Gemini API 호출 (POSITIVE, NEGATIVE, NEUTRAL 분류 및 -1.0~1.0 점수 부여)
   - 출력: Kafka `analyzed-chat-topic`으로 Produce
3. **`neul-core-api` (Spring Boot)**
   - 역할: 분석된 데이터 저장 및 클라이언트 제공
   - DB: PostgreSQL (R2DBC 비동기 저장), Redis (실시간 지표 Hash 집계)
   - 클라이언트 통신: SSE(Server-Sent Events)를 통해 실시간 브로드캐스팅 (`/api/v1/stream/{roomId}`)

## 4. 데이터베이스 및 캐시 규격
코드를 작성할 때 다음의 스키마와 데이터 구조를 반영하세요.

### 4.1 PostgreSQL (`analyzed_chats` 테이블)
- `id` (BIGSERIAL, PK)
- `message_id` (VARCHAR(255), UNIQUE, NOT NULL)
- `room_id` (VARCHAR(255), INDEX, NOT NULL)
- `content` (TEXT)
- `emotion_type` (VARCHAR(50))
- `emotion_score` (DOUBLE PRECISION)
- `analyzed_at` (TIMESTAMP)

### 4.2 Redis (실시간 통계 Hash)
- Key: `room:{roomId}:stats`
- Field-Value: `POSITIVE` (int), `NEGATIVE` (int), `NEUTRAL` (int), `TOTAL_COUNT` (int)

### 4.3 Kafka Topic Payload
- **raw-chat-topic:** `messageId`, `roomId`, `sender`, `content`, `timestamp`
- **analyzed-chat-topic:** `messageId`, `roomId`, `content`, `emotion(type, score)`, `analyzedAt`

## 5. 필수 API 명세
컨트롤러(Controller) 구현 시 아래의 엔드포인트와 응답 구조를 정확히 구현하세요.

- **[POST] `/api/v1/broadcasts`** : 채팅 수집 트리거 (모니터링 시작)
- **[GET] `/api/v1/stream/{roomId}`** : 실시간 감정 스트리밍 (SSE). `Accept: text/event-stream` 헤더 지원. `chat_analyzed` 및 `stats_update` 이벤트 푸시.
- **[GET] `/api/v1/broadcasts/{roomId}/stats`** : 방송 종료 후 최종 통계 조회.

## 6. 추가 구현 및 확장 고려사항 (Advanced)
에이전트가 아키텍처를 개선하거나 문제를 해결할 때 다음을 적극 도입하세요.
- **DLQ (Dead Letter Queue):** Gemini API 500 에러 처리용 `analyzer-dlq-topic` 카프카 로직 구성.
- **DB 파티셔닝:** `analyzed_chats` 테이블의 `created_at` 기준 월별(Monthly) 파티셔닝 DDL 작성.
- 프론트엔드 연동을 위한 `EventSource` 호환성(CORS 등) 유지.
