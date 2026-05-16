# 각(Gak) — 치지직 스트리머 분석 대시보드

스트리밍에 사용할 수 있는 투표, 룰렛 기능을 제공하고   
다시보기에서 편집 후보 구간을 빠르게 찾을 수 있도록 돕는 분석 도구입니다.

---
## 미리보기  
1. 메인 랜딩 페이지  
   <img width="1470" height="782" alt="Image" src="https://github.com/user-attachments/assets/9c8f9877-4725-4195-86ed-f324efa04134" />

2. VOD 하이라이트 추출
    <img width="1470" height="802" alt="Image" src="https://github.com/user-attachments/assets/0aba945c-7fb4-4454-a050-f1166f1e1051" />

---

## AI 협업 방식

이 프로젝트는 개발 전 과정에서 Claude(Anthropic)를 적극적으로 활용했습니다.  
단순 코드 생성이 아니라, 설계 결정·보안 검토·디버깅·문서화를 포함한 **전 주기 페어 프로그래밍** 방식으로 운영했습니다.

---

### 개발자 역할 및 기술 이해도

AI와 협업할 때 개발자의 기술 이해도는 지시의 품질과 결과물의 신뢰도를 결정합니다.  
이 프로젝트에서 개발자가 직접 판단하고 검증한 기술 영역은 다음과 같습니다.

#### 비동기 파이프라인

Spring WebFlux / Project Reactor의 비동기 모델을 이해하고 있어, AI가 생성한 코드의 구독 시점·백프레셔·에러 전파 방식을 직접 검토했습니다.

- SSE 구독 이전 메시지 유실 문제를 `Sinks.multicast()` → `Sinks.replay(100)` 교체로 해결한 배경을 직접 파악
- `doFinally`를 통한 성공·실패·취소 모든 경로에서의 리소스 반납 패턴을 코드 리뷰에서 검증

#### 분산 시스템 설계

Kafka, Redis의 동작 원리를 이해하고 있어 단순 사용이 아닌 설계 수준의 결정을 내렸습니다.

- Kafka 토픽 파티션 키를 `roomId`로 설정해 방별 메시지 순서 보장 — API 한도 우회를 위한 NID WebSocket 직접 연동 판단
- VOD 동시성 제한을 in-memory가 아닌 Redis 카운터 + TTL로 설계해 수평 확장 가능성 확보
- `vod:active:global`, `vod:active:user:{ownerId}`, `vod:owner:{videoNo}` 키 구조 직접 설계

#### 보안 모델

인증·인가 취약점을 이해하고 있어 AI가 놓친 부분을 직접 요구사항으로 제시했습니다.

- `OwnerIdentityResolver`의 헤더·쿼리 폴백이 IDOR 취약점임을 먼저 인지하고 수정 지시
- HMAC-SHA256 서명 + Redis 세션 바인딩 구조로 토큰 탈취 후 즉시 revocation 설계
- `InternalAccessFilter`에서 불일치 시 404 반환(경로 존재 자체를 숨기는 방어)을 의도적으로 선택

#### LLM 시스템 통합

LLM을 파이프라인에 통합할 때 발생하는 신뢰 경계 문제를 이해하고 있어, AI 생성 코드가 LLM 출력을 그대로 신뢰하는 구조적 문제를 직접 발견하고 가드레일 설계를 지시했습니다.

---

### 가드레일 설계

이 프로젝트에서 가드레일은 두 층으로 적용됩니다.  
**Claude에게 부여한 행동 규칙**과 **제품 코드 안에 박힌 LLM 방어 로직**입니다.

#### 1. AI 에이전트(Claude) 가드레일 — `CLAUDE.md`

```
IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.
```

Claude가 코드를 탐색할 때 파일을 무작정 읽기 전에 **지식 그래프로 구조를 파악**하도록 강제했습니다.

| 규칙 | 이유 |
|------|------|
| `semantic_search_nodes` / `query_graph` 우선 | 파일 스캔보다 호출 관계·의존성을 먼저 파악 |
| `get_impact_radius` 로 영향 범위 확인 후 수정 | 수정 전 파급 범위를 모르면 안전하지 않은 변경이 될 수 있음 |
| `detect_changes` + `get_review_context` 로 코드 리뷰 | 전체 파일을 읽지 않고 변경된 부분의 맥락만 효율적으로 파악 |
| Grep/Glob/Read는 그래프가 커버하지 못할 때만 허용 | 토큰 낭비와 컨텍스트 오염 방지 |

**반영된 철학:** AI도 코드를 "읽기" 전에 "이해"해야 한다. 구조를 모르는 상태에서 파일을 읽으면 잘못된 국소적 판단을 내릴 수 있다.

#### 2. 제품 코드 안의 LLM 가드레일 — `OllamaAnalyzerService`

LLM은 신뢰할 수 없는 외부 시스템 경계입니다. 입력과 출력 양쪽에 강제 검증을 적용했습니다.

**입력 가드레일**

```
빈 채팅 제거 → 배치 크기 상한(MAX_BATCH_SIZE=30) → 총 문자 수 상한(MAX_INPUT_CHARS=3000)
```

- 상한 초과 시 `gak.llm.batch.capped` 메트릭 기록 — 가드레일 발동이 눈에 보이도록
- 모든 입력이 걸러지면 LLM 호출 없이 즉시 반환 — 불필요한 API 호출 차단

**출력 가드레일**

```
감정 키 7개 완결성 검증 → 점수 [0.0, 1.0] 클램핑 → 합계 < 0.001이면 NEUTRAL 교정
```

- LLM이 키를 빠뜨려도 DB에 불완전한 데이터가 저장되지 않도록 강제
- 점수 범위를 벗어난 값을 그대로 저장하면 하이라이트 선별 로직이 오동작할 수 있음

**동시성 가드레일**

```java
// Before: 조용한 데이터 손실
if (isProcessing.get()) return Mono.just(List.of());

// After: 관측 가능한 스킵
if (!llmSlot.tryAcquire()) {
    recordCount("gak.llm.batch.skipped");
    return Mono.just(List.of());
}
return doAnalyzeBatch(capped).doFinally(ignored -> llmSlot.release());
```

`AtomicBoolean`의 조용한 손실을 `Semaphore + 메트릭`으로 교체해, 스킵이 발생했을 때 추적 가능하게 만들었습니다.

**VOD 분석 슬롯 — fail-open 의식적 선택**

Redis 장애 시 VOD 분석은 허용(fail-open), 인증 검증 실패 시 접근은 차단(fail-secure).  
같은 Redis 의존이라도 **무엇을 보호하느냐에 따라 장애 전략을 다르게 설정**했습니다.

| 시스템 | Redis 장애 시 | 이유 |
|--------|-------------|------|
| `OwnerAccessFilter` 세션 검증 | 401 반환 (fail-secure) | 인증 실패는 보안 문제 |
| `VodAnalysisSlotService` 동시성 제한 | 분석 허용 (fail-open) | 카운터가 틀려도 서비스 중단보다 낫다 |

**반영된 철학:** LLM은 예측 불가능한 외부 시스템이다. 입력을 제한하고 출력을 검증하지 않으면 파이프라인 전체가 LLM의 품질에 종속된다. 또한 실패는 관측 가능해야 하며, 조용한 손실은 없어야 한다.

---

### AI 활용 방식

| 단계 | 활용 내용 |
|------|----------|
| **설계** | 아키텍처 트레이드오프 검토, ADR 초안, 패키지 구조 제안 |
| **구현** | Spring Boot WebFlux 필터·서비스, Next.js API proxy, Kafka 파이프라인 코드 생성 |
| **보안 검토** | 취약점 발견 요청, `InternalAccessFilter` · `OwnerValidationFilter` 구현 |
| **디버깅** | SSE 메시지 유실, Kafka consumer group, Flyway 오류 원인 분석 |
| **리팩터링** | neul → gak 전체 이관, 패키지·환경변수·Redis 키 일괄 정리 |
| **문서화** | 트러블슈팅 가이드, 보안 강화 기록, 프로덕션 배포 체크리스트, 이 README |
| **코드 리뷰** | 변경 영향 범위 분석, 잠재 버그 지적, 개선 방향 제안 |

### AI 활용 역량 포인트

**지시의 정밀도** — "코드 짜줘"가 아니라 요구사항과 제약 조건을 명확히 전달했습니다.  
예: *"쿠키 검증 실패 시 헤더·쿼리 폴백을 완전히 제거하고 쿠키만 허용해줘. X-Chzzk-Owner-Id 헤더 하나로 타인 계정 위조가 가능하기 때문이야."*

**결과물 검증** — AI가 생성한 코드를 그대로 쓰지 않고 직접 실행·테스트 후 문제를 피드백해 반복 개선했습니다.

**범위 판단** — 구현 세부사항은 AI에게, 아키텍처 결정과 최종 승인은 개발자가 유지했습니다.

**컨텍스트 관리** — ADR, 트러블슈팅 이력, 보안 모델을 문서로 축적해 AI가 매 대화마다 일관된 판단을 내릴 수 있는 환경을 만들었습니다.

---

## 목차

- [구현된 기능](#구현된-기능)
- [아키텍처](#아키텍처)
- [기술 스택](#기술-스택)
- [로컬 실행](#로컬-실행)
- [트러블슈팅](#트러블슈팅)
- [AI 협업 방식](#ai-협업-방식)
- [문서 목록](#문서-목록)

---

## 구현된 기능

### 인증 & 보안

| 기능 | 설명 |
|------|------|
| CHZZK OAuth 로그인 | 치지직 공식 OAuth 흐름. `frontend → Next proxy → collector → callback` |
| Owner 전용 대시보드 | 로그인한 스트리머 본인 채널에만 접근 허용 |
| HMAC-SHA256 토큰 | `GAK_OWNER_ASSERTION` 쿠키 — 서명 + 세션 바인딩 구조 |
| 즉시 revocation | 로그아웃 시 Redis 세션 키 삭제 → 탈취된 토큰 무효화 |
| `OwnerAccessFilter` | `/api/v1/vod/**`, `/api/v1/me/**` 등 보호 경로 쿠키 검증 |
| `InternalAccessFilter` | `/internal/**` 마이크로서비스 전용 경로 — `X-Internal-Secret` 헤더 검증, 불일치 시 404 |
| `OwnerValidationFilter` | collector 단 소유자 검증 필터 |
| Secure 쿠키 플래그 | `GAK_COOKIE_SECURE=true` 설정 시 HTTPS 전용 쿠키 전송 |

### 라이브 채팅 분석

| 기능 | 설명 |
|------|------|
| 실시간 채팅 수집 | NID WebSocket 프로토콜 직접 연동 (API 일일 한도 우회) |
| 감정 분석 파이프라인 | collector → Kafka → analyzer → core-api → SSE → frontend |
| 7가지 감정 분류 | 2초 배치 단위 분석, Ollama 로컬 LLM 또는 휴리스틱 분석기 |
| 실시간 집계 | Redis Hash 기반 O(1) 통계 조회 |
| SSE 스트리밍 | `Sinks.replay(100)` — 구독 이전 메시지 유실 방지 |
| Mock 채팅 주입 | 실방송 없이 파이프라인 검증 가능 |

### VOD 분석 & 하이라이트

| 기능 | 설명 |
|------|------|
| VOD 메타데이터 조회 | VOD 번호 또는 URL 입력 → 메타데이터 카드 표시 |
| 채팅 크롤링 | VOD 전체 채팅 수집 후 분석 파이프라인으로 전달 |
| 분석 상태 관리 | `REQUESTED → CRAWLING → ANALYZING → COMPLETED / FAILED` |
| 편집 후보 하이라이트 | `intensityScore`, `transitionScore`, `editabilityScore` 기반 구간 선별 |
| 버킷 분산 선택 | 시간대별 대표 구간 확보 → 앞쪽 쏠림 방지 |
| 타임라인 시각화 | 전체 타임라인 + 하이라이트 마커 — timeline 실패 시 highlights fallback |
| 상태 adaptive polling | 분석 진행 중 폴링 간격 자동 조정 |
| VOD 동시성 슬롯 | 동시 분석 요청 수 제어 |

### 투표 & 룰렛

| 기능 | 설명 |
|------|------|
| 실시간 투표 | 시작·중지 버튼 분리, 투표자 전체 표시, 채팅 기록 모달 |
| 도네이션 기반 가중 룰렛 | 도네이션 금액에 비례한 가중 선택 전략 |
| 성인 방송 자동 감지 | 조건 충족 시 룰렛/투표 자동 처리 분기 |

### RAG 하이라이트 추천

| 기능 | 설명 |
|------|------|
| 벡터 임베딩 | `nomic-embed-text` 모델로 하이라이트 임베딩 저장 (pgvector) |
| 혼합 추천 전략 | 벡터 유사도 + 규칙 기반 스코어 혼합 |

### UI / UX

| 기능 | 설명 |
|------|------|
| 치지직 브랜드 디자인 | 공식 브랜드 컬러 및 디자인 시스템 적용 |
| 3탭 구조 | 투표 / 룰렛 / VOD 탭 분리 |
| GlobalErrorHandler | 로그인 만료·502 에러 감지 및 UI 메시지 표시 |
| 프로덕션 콘솔 제거 | `next.config.ts`에서 `console.*` 자동 제거 |
| Dev Seed 도구 | 도네이션·투표 데이터 주입 API (`/dev/seed`) |

---

## 아키텍처

```
Browser
  │
  └─ Next.js (3000)          ← UI + API Proxy (브라우저가 backend 직접 호출 안 함)
       │
       ├─ /api/chzzk/*  →  collector (8081)   ← CHZZK 로그인, 채팅 수집, VOD 크롤링
       ├─ /api/v1/*     →  core-api  (8083)   ← 분석 결과 저장/조회, SSE, 접근 제어
       └─ /api/v2/*     →  core-api  (8083)

collector ─(Kafka)─► analyzer (8082)  ← 감정 분석, 편집 후보 계산
                          │
                    (Kafka: vod-analysis-complete-topic)
                          │
                     core-api ─► PostgreSQL (pgvector)
                          │
                        Redis  ← 세션, 실시간 집계
```

**요청 흐름 — 인증**

```
로그인 버튼
  → Next proxy → collector /chzzk/login
  → CHZZK OAuth callback
  → Redis 세션 저장 + GAK_OWNER_ASSERTION 쿠키 발급
  → 이후 모든 요청에서 OwnerAccessFilter가 쿠키 서명·세션 검증
```

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| Frontend | Next.js 15, TypeScript, Tailwind CSS |
| Backend | Spring Boot (WebFlux), Java 17 |
| 메시지 브로커 | Apache Kafka |
| 캐시·세션 | Redis 7 |
| DB | PostgreSQL 15 + pgvector |
| LLM | Ollama (로컬) — `nomic-embed-text`, 감정 분석 모델 |
| 스키마 관리 | Flyway |
| 인프라 | Docker Compose |
| 장애 격리 | Resilience4j Circuit Breaker |

---

## 로컬 실행

### 사전 준비

- Docker Desktop 실행 중
- JDK 17
- Node.js / npm
- `backend/.env` — CHZZK OAuth 키 포함

`backend/.env` 최소 항목 (`backend/.env.example` 참고):

```env
CHZZK_CLIENT_ID=...
CHZZK_CLIENT_SECRET=...
GAK_OWNER_TOKEN_SECRET=...
GAK_INTERNAL_API_SECRET=...
GAK_COOKIE_SECURE=false   # 로컬은 false, 프로덕션은 true
```

로컬 5432 포트 충돌 시:

```env
GAK_POSTGRES_HOST_PORT=55432
```

### 실행 순서

```bash
# 1. 인프라 (PostgreSQL, Redis, Kafka, Zookeeper)
cd backend
docker compose up -d

# 2. core-api (Flyway 스키마 자동 반영)
./gradlew :core-api:bootRun

# 3. analyzer
./gradlew :analyzer:bootRun

# 4. collector
./gradlew :collector:bootRun

# 5. frontend
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:3000` 접속 → 로그인 → 본인 채널 대시보드 진입

### Mock 채팅 주입 (방송 없이 테스트)

```bash
curl -X POST "http://localhost:8081/api/v1/dev/mock-chat/{channelId}?count=10"
```

### Dev Seed (도네이션·투표 데이터)

```bash
curl -X POST "http://localhost:8083/dev/seed/{channelId}"
```

---

## 트러블슈팅

### `${CHZZK_CLIENT_ID}`가 URL에 그대로 노출

**원인** — `backend/.env`가 Spring에 로드되지 않음

**해결** — `collector/src/main/resources/application.yaml`에 아래 항목 추가:

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
```

---

### CORS 에러처럼 보이는데 실제로는 preflight 차단

**원인** — owner 검증 필터가 브라우저의 `OPTIONS` preflight 요청을 막음

**해결** — 브라우저가 `8081`, `8083` 포트를 직접 호출하지 않도록 반드시 **Next.js API proxy 경유**로 요청해야 합니다.

---

### `gak_app` 비밀번호 인증 실패

**원인** — 로컬 PostgreSQL 서비스와 Docker PostgreSQL이 같은 포트(5432)에서 동시에 실행 중

**해결** — 로컬 PostgreSQL을 중지한 뒤 Docker 컨테이너 상태를 확인:

```bash
# macOS
brew services stop postgresql@15

# Windows
Get-Service postgresql-x64-15 | Stop-Service

docker ps  # gak-postgres 컨테이너가 실행 중인지 확인
```

---

### `relation "vod_timeline_points" does not exist`

**원인** — core-api 최초 부팅 시 Flyway 마이그레이션이 실행되지 않음

**해결** — core-api 로그에서 `Flyway migrate` 성공 메시지 확인. 수동 `schema.sql` 적용은 더 이상 사용하지 않으며, core-api를 먼저 실행해야 스키마가 생성됩니다.

---

### VOD 분석 상태가 `ANALYZING`에서 멈춤

**원인** — `vod-analysis-complete-topic` 이벤트 체인이 끊겨 상태 전이가 일어나지 않음

**해결** — 순서대로 확인:

1. analyzer 로그에서 완료 이벤트 발행 여부 확인
2. collector consumer group이 해당 토픽을 구독 중인지 확인
3. core-api에 highlights가 이미 존재하면 collector가 자동으로 `COMPLETED`로 보정

---

### VOD 하이라이트가 앞쪽 시간대에만 몰림

**원인** — 채팅 밀도가 높은 초반 구간이 점수를 독점해 후반 구간이 선별되지 않음

**해결** — 현재 로직은 시간대 버킷 대표를 먼저 확보한 뒤 남은 슬롯을 전역 상위로 채웁니다. `transitionScore` 가중치를 높이면 조용하다가 급증하는 구간을 더 적극적으로 선별할 수 있습니다.

---

### 타임라인이 비어 있고 하이라이트만 보임

**원인** — `vod_timeline_points` 저장 또는 조회 실패

**해결** — frontend는 `timeline`이 비면 `highlights` 기반 fallback 타임라인을 자동 생성하므로 화면이 완전히 비지는 않습니다. 정확한 전체 타임라인이 필요하다면 core-api의 timeline 저장 경로 로그를 확인하세요.

---

### 로그인 만료 후 API 요청이 자동으로 실패

**원인** — 세션 만료로 서버가 401을 반환하고, `GlobalErrorHandler`가 이를 감지해 리다이렉트

**해결** — 브라우저 쿠키(`GAK_OWNER_ASSERTION`)를 삭제하고 재로그인. 502 에러도 동일하게 UI 메시지로 표시됩니다.

---

### 로그아웃 후에도 탈취된 토큰이 작동하는 것 같을 때

**원인** — stateless 토큰은 서명만 유효하면 서버가 추가 검증 없이 수락하는 구조

**해결** — 이 프로젝트는 Redis 세션 바인딩으로 즉시 revocation을 지원합니다. 로그아웃하면 `gak:owner-session:{ownerId}` 키가 즉시 삭제되어 이후 요청은 세션 불일치로 401을 반환합니다. 프로덕션에서는 반드시 `GAK_COOKIE_SECURE=true`로 설정해 HTTP 도청을 차단하세요.

---


## 문서 목록

자세한 내용은 [`docs/`](docs/) 폴더를 참고하세요.

| 문서 | 내용 |
|------|------|
| [00_PROJECT_MASTER_HISTORY](docs/00_PROJECT_MASTER_HISTORY.md) | 프로젝트 전체 흐름 및 문서 인덱스 |
| [01_ADR](docs/01_ADR.md) | 아키텍처 결정 기록 |
| [02_troubleshooting](docs/02_troubleshooting.md) | 통합 트러블슈팅 가이드 (상세) |
| [03_run_guide](docs/03_run_guide.md) | 실행 순서 및 운영 체크 |
| [04_technical_concepts](docs/04_technical_concepts.md) | Kafka, Redis, SSE, Resilience4j 등 기술 개념 |
| [05_developer_handover](docs/05_developer_handover.md) | 구조 및 핵심 파일 핸드오버 |
| [06_testing_strategy](docs/06_testing_strategy.md) | 테스트 전략 및 E2E |
| [07_native_optimization_guide](docs/07_native_optimization_guide.md) | Java/Rust 최적화 방향 |
| [08_performance_migration_log](docs/08_performance_migration_log.md) | 성능 이전 로그 |
| [09_evolution_roadmap](docs/09_evolution_roadmap.md) | 로드맵 및 구현 체크리스트 |
| [12_gak_v2_implementation_plan](docs/12_gak_v2_implementation_plan.md) | v2 장기 구현 계획 |
| [13_owner_auth_revision](docs/13_owner_auth_revision.md) | owner 인증 구조 변경 메모 |
| [14_vod_concurrency_plan](docs/14_vod_concurrency_plan.md) | VOD 동시성 및 안정성 계획 |
| [15_emotion_analysis_experiment_plan](docs/15_emotion_analysis_experiment_plan.md) | 편집 후보 중심 감정 분석 실험 |
| [16_personalized_vod_highlight_plan](docs/16_personalized_vod_highlight_plan.md) | 개인화 VOD 편집 후보 확장 계획 |
| [17_auth_reliability_test_scenarios](docs/17_auth_reliability_test_scenarios.md) | 인증 신뢰성 테스트 시나리오 |
| [18_llm_guardrail_plan](docs/18_llm_guardrail_plan.md) | LLM 입출력 가드레일 설계 |
| [19_status_polling_plan](docs/19_status_polling_plan.md) | VOD 분석 상태 폴링 전략 |
| [20_startup_recovery](docs/20_startup_recovery.md) | 서비스 재기동 복구 절차 |
| [21_phase1_to_5_test_spec](docs/21_phase1_to_5_test_spec.md) | 단계별 통합 테스트 명세 |
| [22_security_hardening](docs/22_security_hardening.md) | 보안 강화 작업 기록 |
| [23_production_deploy_checklist](docs/23_production_deploy_checklist.md) | 프로덕션 배포 체크리스트 |
| [24_session_theft_defense](docs/24_session_theft_defense.md) | 세션 탈취 방어 전략 |
| [25_ui_naming_update](docs/25_ui_naming_update.md) | UI 네이밍 최신화 기록 |
