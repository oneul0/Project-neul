# 각(Gak) — 치지직 스트리머 분석 대시보드

스트리머 본인이 라이브 채팅 반응을 실시간으로 파악하고,  
다시보기에서 편집 후보 구간을 빠르게 찾을 수 있도록 돕는 분석 도구입니다.

---

## 목차

- [구현된 기능](#구현된-기능)
- [아키텍처](#아키텍처)
- [기술 스택](#기술-스택)
- [로컬 실행](#로컬-실행)
- [트러블슈팅](#트러블슈팅)
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

### `${CHZZK_CLIENT_ID}`가 URL에 그대로 노출될 때

`backend/.env`가 Spring에 로드되지 않은 경우입니다.

`collector/src/main/resources/application.yaml`에 아래 항목이 있는지 확인:

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
```

---

### CORS 에러처럼 보이는데 실제로는 preflight가 막힐 때

owner 검증 필터가 `OPTIONS` preflight를 차단한 경우입니다.  
브라우저가 `8081`, `8083`을 직접 호출하지 않도록 반드시 **Next API proxy 경유**로 요청해야 합니다.

---

### `gak_app` 비밀번호 인증 실패

로컬 PostgreSQL 서비스와 Docker PostgreSQL이 동시에 실행 중인 경우입니다.

```bash
# 로컬 PostgreSQL 중지 (macOS)
brew services stop postgresql@15

# Windows
Get-Service postgresql-x64-15 | Stop-Service

docker ps  # gak-postgres 컨테이너 실행 중인지 확인
```

---

### `relation "vod_timeline_points" does not exist`

core-api 부팅 시 Flyway 마이그레이션이 실행되지 않은 경우입니다.

core-api 로그에서 `Flyway migrate` 성공 여부를 확인하세요.  
수동 `schema.sql` 반영은 더 이상 사용하지 않습니다.

---

### VOD 분석 상태가 `ANALYZING`에서 멈출 때

`vod-analysis-complete-topic` 이벤트 체인이 끊긴 경우입니다.

1. analyzer 로그에서 완료 이벤트 발행 여부 확인
2. collector consumer group이 해당 토픽을 구독 중인지 확인
3. core-api에 highlights가 이미 존재하면 collector가 자동으로 `COMPLETED`로 보정합니다

---

### VOD 하이라이트가 앞쪽 시간대에 몰릴 때

채팅 밀도가 높은 구간이 항상 상위 점수를 독점하는 현상입니다.  
현재 로직은 시간대 버킷 대표를 우선 확보하고, 남은 슬롯을 전역 상위로 채웁니다.  
`transitionScore`를 높이면 조용하다가 급증하는 구간 가중치를 높일 수 있습니다.

---

### 타임라인이 비어 있고 하이라이트만 보일 때

`vod_timeline_points` 저장 또는 조회가 실패한 경우입니다.  
frontend는 `timeline`이 비면 `highlights` 기반 fallback 타임라인을 자동 생성하므로 화면이 완전히 비지는 않습니다.  
정확한 전체 타임라인이 필요하다면 core-api의 timeline 저장 경로 로그를 확인하세요.

---

### 로그인 만료 후 API 요청이 자동으로 실패할 때

`GlobalErrorHandler` 컴포넌트가 401 응답을 감지하면 로그인 페이지로 리다이렉트합니다.  
502 에러도 동일하게 UI 메시지를 표시합니다.  
상태가 이상하면 브라우저 쿠키(`GAK_OWNER_ASSERTION`)를 삭제하고 재로그인하세요.

---

### 토큰 탈취 시 대응

로그아웃하면 Redis에서 `gak:owner-session:{ownerId}` 키가 삭제됩니다.  
이후 탈취된 토큰으로 요청이 와도 세션 불일치로 401을 반환합니다.  
프로덕션 환경에서는 `GAK_COOKIE_SECURE=true`로 설정해 HTTP 도청을 차단하세요.

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
| [22_security_hardening](docs/22_security_hardening.md) | 보안 강화 작업 기록 |
| [23_production_deploy_checklist](docs/23_production_deploy_checklist.md) | 프로덕션 배포 체크리스트 |
| [24_session_theft_defense](docs/24_session_theft_defense.md) | 세션 탈취 방어 전략 |
