# 각(Gak) — 치지직 스트리머 분석 대시보드

스트리밍에 사용할 수 있는 투표, 룰렛 기능을 제공하고
다시보기에서 편집 후보 구간을 빠르게 찾을 수 있도록 돕는 분석 도구입니다.

---

## 미리보기

<!-- 메인 배너 이미지 -->
<br/>
<p align="center">
  <img width="1470" height="782" alt="Image" src="https://github.com/user-attachments/assets/9c8f9877-4725-4195-86ed-f324efa04134" />
</p>
<br/>

---

## ✨ 앱 플로우 소개

|  🔐 로그인  |  🎬 VOD 하이라이트  |  🎯 투표 & 룰렛  |
|:---:|:---:|:---:|
| <!-- 로그인 스크린샷 --> | <img width="1470" height="802" alt="Image" src="https://github.com/user-attachments/assets/0aba945c-7fb4-4454-a050-f1166f1e1051" /> | <!-- 투표/룰렛 스크린샷 --> |
| CHZZK OAuth 로그인 | 편집 후보 구간 자동 선별 | 채팅 기반 투표 & 도네이션 룰렛 |

---

## 🚀 핵심 기능

### 🔒 인증 & 보안
- CHZZK OAuth 로그인 — 본인 채널에만 접근 허용
- HMAC-SHA256 서명 + Redis 세션 바인딩으로 즉시 토큰 revocation
- `OwnerAccessFilter` / `InternalAccessFilter` 이중 보호

### 💬 채팅 수집 & 이벤트 처리
- NID WebSocket 직접 연동으로 실시간 채팅 수집 (2초 배치)
- 투표 명령(`!투표 N`) → Redis 집계 / 도네이션·구독 → SSE로 룰렛 트리거
- SSE `Sinks.replay(100)` — 구독 이전 메시지 유실 방지

### 🎬 VOD 분석 & 하이라이트
- `intensityScore`, `transitionScore`, `editabilityScore` 기반 편집 후보 선별
- 시간대 버킷 분산으로 앞쪽 쏠림 방지
- 분석 상태 adaptive polling (`REQUESTED → CRAWLING → ANALYZING → COMPLETED`)

### 🎯 투표 & 룰렛
- 실시간 투표 — 시작·중지 분리, 채팅 기록 모달 제공
- 도네이션 금액 비례 가중 룰렛
- 성인 방송 자동 감지 및 분기 처리

---

## 🏗️ 아키텍처

```
Browser
  └─ Next.js (3000)          ← UI + API Proxy
       ├─ /api/chzzk/*  →  collector (8081)   ← 로그인, 채팅 수집, VOD 크롤링
       └─ /api/v1/*     →  core-api  (8083)   ← 분석 결과, SSE, 접근 제어

collector ─(Kafka)─► analyzer (8082)  ← 투표·도네이션 처리, VOD 편집 후보 계산
                          │
                     core-api ─► PostgreSQL (pgvector)
                          │
                        Redis  ← 세션, 실시간 집계
```

---

## ⚡ 핵심 챌린지

### 비동기 파이프라인
SSE 구독 이전 메시지 유실 문제를 `Sinks.multicast()` → `Sinks.replay(100)` 교체로 해결했습니다. `doFinally`를 통해 성공·실패·취소 모든 경로에서 리소스를 반납합니다.

### 분산 시스템 설계
VOD 동시성 제한을 in-memory가 아닌 **Redis 카운터 + TTL**로 설계해 수평 확장 가능성을 확보했습니다. Kafka 파티션 키를 `roomId`로 설정해 방별 메시지 순서를 보장합니다.

### LLM 가드레일 (VOD 하이라이트 분석)
| 구분 | 내용 |
|------|------|
| 입력 | 빈 채팅 제거 → 배치 크기 상한(30) → 총 문자 상한(3,000자) |
| 출력 | 감정 키 7개 완결성 검증 → 점수 [0.0, 1.0] 클램핑 |
| 동시성 | `AtomicBoolean` → `Semaphore + 메트릭` 교체로 스킵 추적 가능화 |

### fail-open vs fail-secure
Redis 장애 상황에서 **인증은 차단(fail-secure)**, **VOD 슬롯 카운팅은 허용(fail-open)**으로 보호 대상에 따라 장애 전략을 다르게 설계했습니다.

---

## 🛠️ 기술 스택

| 영역 | 기술 |
|------|------|
| Frontend | Next.js 15, TypeScript, Tailwind CSS |
| Backend | Spring Boot (WebFlux), Java 17 |
| 메시지 브로커 | Apache Kafka |
| 캐시·세션 | Redis 7 |
| DB | PostgreSQL 15 + pgvector |
| LLM | Ollama — `nomic-embed-text`, 감정 분석 모델 |
| 인프라 | Docker Compose, Resilience4j Circuit Breaker |

---

## 💻 로컬 실행

### 사전 준비
- Docker Desktop, JDK 17, Node.js
- `backend/.env` — CHZZK OAuth 키 포함

```env
CHZZK_CLIENT_ID=...
CHZZK_CLIENT_SECRET=...
GAK_OWNER_TOKEN_SECRET=...
GAK_INTERNAL_API_SECRET=...
GAK_COOKIE_SECURE=false
```

### 실행 순서

```bash
# 1. 인프라 (PostgreSQL, Redis, Kafka)
cd backend && docker compose up -d

# 2. 백엔드 서비스 (순서 중요)
./gradlew :core-api:bootRun
./gradlew :analyzer:bootRun
./gradlew :collector:bootRun

# 3. 프론트엔드
cd frontend && npm install && npm run dev
```

브라우저에서 `http://localhost:3000` 접속 후 로그인하면 본인 채널 대시보드로 진입합니다.

### Mock 데이터 주입 (방송 없이 테스트)

```bash
# 채팅 주입
curl -X POST "http://localhost:8081/api/v1/dev/mock-chat/{channelId}?count=10"

# 도네이션·투표 데이터
curl -X POST "http://localhost:8083/dev/seed/{channelId}"
```

---

## 🔧 주요 트러블슈팅

| 문제 | 원인 | 해결 |
|------|------|------|
| `${CHZZK_CLIENT_ID}` URL 노출 | `.env`가 Spring에 미로드 | `application.yaml`에 `spring.config.import` 추가 |
| CORS 에러처럼 보이는 preflight 차단 | 검증 필터가 `OPTIONS` 요청을 막음 | 반드시 **Next.js API proxy 경유** 요청 |
| VOD 분석이 `ANALYZING`에서 멈춤 | 이벤트 체인이 끊겨 상태 전이 불발 | analyzer 로그 → consumer group 구독 확인 |
| 하이라이트가 앞쪽에만 몰림 | 초반 채팅 밀도가 점수 독점 | 시간대 버킷 분산 + `transitionScore` 가중치 조정 |
| 로컬 PostgreSQL 포트 충돌 (5432) | Docker와 로컬 PG 동시 실행 | 로컬 PG 중지 후 `docker ps`로 컨테이너 확인 |

---

## 👋 팀원 소개

> 팀원 정보를 여기에 추가해주세요.

---

## 🏷️ 바로가기

[Wiki](#) · [팀 노션](#) · [그라운드 룰](#) · [컨벤션](#) · [회의록](#) · [설계 문서](docs/)
