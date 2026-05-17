# 29. 온보딩 가이드

> 이 문서는 프로젝트를 처음 보는 사람이 구조를 빠르게 파악할 수 있도록 작성된 종합 온보딩 문서다.  
> 상세 내용은 각 링크 문서를 참조한다.

---

## 1. 프로젝트 한 줄 요약

**각(Gak)**: 치지직 스트리머를 위한 Owner 전용 대시보드. 실시간 채팅 분석, 투표·룰렛 운영, VOD 채팅 크롤링 기반 편집 후보 구간 자동 선별 기능을 제공한다.

---

## 2. 서비스 구성 (포트 기준)

| 서비스 | 포트 | 역할 |
|--------|------|------|
| `frontend` | 3000 | Next.js UI + API Proxy |
| `collector` | 8081 | 치지직 OAuth, 라이브 채팅 수집, VOD 크롤링 |
| `analyzer` | 8082 | 감정 분석(Ollama), VOD 하이라이트 계산 |
| `core-api` | 8083 | 분석 결과 저장·조회, SSE, 접근 제어 |

**브라우저는 백엔드를 직접 호출하지 않는다.** 모든 요청은 Next.js API Route(`/api/chzzk/*`, `/api/v1/*`, `/api/v2/*`)가 중계한다.

---

## 3. 필수 인프라

```
Docker Compose로 실행:
  - PostgreSQL 15 (5432) — pgvector 익스텐션 포함
  - Redis 7 (6379) — 세션, 실시간 집계, 슬롯 카운터
  - Apache Kafka + Zookeeper (9092)
  - Ollama (11434) — 감정 분석 LLM + nomic-embed-text 임베딩 모델
```

실행 가이드: [`03_run_guide.md`](03_run_guide.md)

---

## 4. 요청 흐름 (전체)

```
Browser
  └─ Next.js (3000)
       ├─ /api/chzzk/* → collector (8081)  ← OAuth 로그인, 채팅 수집, VOD 크롤링
       ├─ /api/v1/*    → core-api  (8083)  ← 분석 결과 저장/조회, SSE
       └─ /api/v2/*    → core-api  (8083)  ← (확장용)

collector ──(Kafka)──► analyzer
analyzer  ──(Kafka)──► core-api
core-api  ──────────── PostgreSQL + Redis
```

---

## 5. 인증 구조

### 로그인 흐름

```
1. 스트리머가 "치지직으로 로그인" 클릭
2. frontend → GET /api/chzzk/login → collector
3. collector가 Chzzk OAuth URL 생성 + Redis에 state 저장 (TTL=10m)
4. 브라우저가 Chzzk 로그인 페이지로 리다이렉트
5. Chzzk → collector /callback?code=&state=
6. collector가 code→token 교환 → channelId 조회
7. HMAC-SHA256 서명된 GAK_OWNER_ASSERTION 쿠키 발급 (HttpOnly)
8. Redis에 gak:owner-session:{channelId} 저장
9. 브라우저가 /channels/{channelId}로 리다이렉트
```

### 요청 검증 (core-api)

```
1. GAK_OWNER_ASSERTION 쿠키 HMAC 서명 검증 → 실패 시 401
2. Redis GET gak:owner-session:{ownerId} → 키 없음·불일치 시 401
3. URL의 channelId == ownerId → 불일치 시 403
```

### 보안 설계 포인트

| 위협 | 방어 |
|------|------|
| XSS 쿠키 탈취 | `HttpOnly=true` |
| MITM | `Secure=true` (프로덕션) |
| 로그아웃 후 토큰 재사용 | Redis 키 삭제 → 즉시 401 |
| 내부 API 직접 접근 | `InternalAccessFilter` — 불일치 시 404 (경로 존재 숨김) |
| IDOR | 헤더 폴백 없이 쿠키·Redis·channelId 3중 검증 |

상세: [`22_security_hardening.md`](22_security_hardening.md), [`24_session_theft_defense.md`](24_session_theft_defense.md)

---

## 6. 실시간 채팅 분석 파이프라인

```
Chzzk NID WebSocket (치지직 내부 프로토콜)
  → collector (채팅 수집, 2초 배치)
  → Kafka: raw-chat-batch-topic (key=roomId → 방별 순서 보장)
  → analyzer (OllamaAnalyzerService)
      ├─ 입력 가드레일: 빈 채팅 제거, MAX_BATCH=30, MAX_CHARS=3000
      ├─ Ollama LLM → 7가지 감정 분류
      └─ 출력 가드레일: 키 완결성, [0,1] 클램핑, 합계<0.001→NEUTRAL
  → Kafka: analyzed-chat-topic
  → core-api → Redis Hash (실시간 집계)
  → SSE (Sinks.replay(100)) → frontend
```

**`Sinks.replay(100)`**: 구독 전에 도착한 최대 100개 메시지를 보관해 구독 직후 유실을 방지한다.

---

## 7. VOD 하이라이트 추출 흐름

전체 6단계 시퀀스 다이어그램: [`26_vod_highlight_sequence_diagrams.md`](26_vod_highlight_sequence_diagrams.md)

### 핵심 설계 포인트

**크롤링**: cursor 기반 페이지네이션으로 VOD 전체 채팅 수집. `visitedCursors` 집합으로 무한 루프 방지. timeout=12s, MAX_RETRIES=2.

**분석 (30초 윈도우)**:
- `intensityScore`: 채팅 밀도, 고유 발화자, burst 신호, z-score, 감정 토큰
- `transitionScore`: 직전 조용한 구간 대비 급증 + 다음 구간 지속 여부
- `editabilityScore`: 메시지 다양성, 키워드 집중도, 대표 채팅 유무
- `totalScore` = intensity×0.55 + transition×0.20 + editability×0.25

**LLM 리뷰**: 상위 12개 후보에 대해 Ollama가 최종 판정 (concurrency=3, timeout=4분). 거절 시 score×0.38.

**분산 선별**: 시간대별 버킷(4~8개)에서 구간당 대표 1개 먼저 확보 → 나머지 쿼터를 전역 상위로 채움. 결과 5~24개.

**동시성 제어 (Redis)**:
- 사용자별 1건 / 전체 3건 동시 분석 제한
- Redis 장애 시 `fail-open` (분석 허용) — 인증 `fail-secure`와 대조됨
- TTL=30분으로 stuck 상태 자동 해제

---

## 8. 데이터베이스 스키마

ERD: [`27_erd.md`](27_erd.md)  
정규화 분석: [`28_normalization_analysis.md`](28_normalization_analysis.md)

### 테이블 목록

| 테이블 | 용도 |
|--------|------|
| `analyzed_chats` | 실시간 채팅 감정 분석 결과 |
| `highlight_records` | 라이브 방송 하이라이트 순간 |
| `vod_highlights` | VOD 편집 후보 구간 |
| `vod_timeline_points` | VOD 타임라인 활동 집계 |
| `user_vod_library` | 스트리머 VOD 목록 |
| `user_vod_activity` | 하이라이트 상호작용 로그 |

스키마 관리: Flyway (`V1~V7__*.sql`). `gak_admin` 계정이 DDL 수행, `gak_app`이 DML 수행.

---

## 9. LLM 가드레일 (`OllamaAnalyzerService`)

```
입력: 빈 채팅 제거 → MAX_BATCH_SIZE=30 → MAX_INPUT_CHARS=3000
출력: 감정 키 7개 완결성 → [0.0, 1.0] 클램핑 → 합계<0.001→NEUTRAL 교정
동시성: Semaphore(1)으로 직렬화 + 메트릭 기록 (gak.llm.batch.skipped)
```

LLM은 신뢰할 수 없는 외부 시스템 경계로 취급한다. 입력 제한 없이는 파이프라인 전체가 LLM 품질에 종속된다.

---

## 10. 트러블슈팅 빠른 참조

| 증상 | 원인 | 해결 |
|------|------|------|
| SSE 구독 직후 메시지 유실 | `Sinks.multicast()` | `Sinks.replay(100)` |
| core-api DB 인증 실패 | 환경변수 계정 불일치 | `application-dev.yaml` 확인 |
| Docker DB 계정 오류 | volume이 구 계정으로 초기화됨 | `docker compose down -v && up -d` |
| VOD 상태 ANALYZING에서 멈춤 | Kafka consumer group 연결 끊김 | consumer group 재기동, 자동 COMPLETED 보정 로직 |
| CORS처럼 보이는 preflight 차단 | OwnerAccessFilter가 OPTIONS 차단 | Next.js proxy 경유 강제 |
| 하이라이트 앞쪽 쏠림 | 초반 채팅 밀도가 점수 독점 | 버킷 대표 우선 확보 후 전역 상위 채우는 2단계 선별 |

상세: [`02_troubleshooting.md`](02_troubleshooting.md)

---

## 11. 코드 탐색 가이드

### 인증 흐름

```
collector/
  ChzzkOAuthController.java      ← 로그인 시작·콜백 처리
  OwnerSessionService.java        ← 세션 발급·검증

core-api/
  filter/OwnerAccessFilter.java  ← HMAC 검증 + Redis 세션 확인
  filter/InternalAccessFilter.java ← 내부 API 차단
  service/OwnerIdentityResolver.java ← exchange에서 ownerId 추출
```

### 실시간 채팅 분석

```
collector/
  ChzzkWebSocketService.java     ← NID WebSocket 연결·수집
  LiveChatBatchPublisher.java    ← 2초 배치 Kafka 발행

analyzer/
  OllamaAnalyzerService.java     ← LLM 감정 분류 + 가드레일
  LiveChatAnalysisListener.java  ← Kafka 소비 → 분석 → 저장

core-api/
  service/ChatSseService.java    ← Sinks.replay(100), SSE 스트리밍
  controller/SseController.java  ← /api/v1/sse/{channelId}
```

### VOD 하이라이트

```
collector/
  VodCollectorController.java    ← POST /crawl, GET /status
  VodChatCrawlerService.java     ← 페이지네이션 크롤링
  VodAnalysisStatusService.java  ← in-memory 상태 머신

analyzer/
  VodHighlightAnalyzer.java      ← 30초 윈도우 집계·점수화·LLM 리뷰

core-api/
  controller/VodController.java          ← 분석 시작, 결과 조회
  service/VodAnalysisSlotService.java    ← Redis 동시성 가드레일
  service/VodHighlightConsumer.java      ← vod-analyzed-topic 소비
  service/VodTimelinePointConsumer.java  ← vod-window-summary-topic 소비
  service/VodAnalysisEventConsumer.java  ← 슬롯 반납
  rag/HighlightEmbeddingService.java     ← pgvector 임베딩 저장
```

---

## 12. 환경 변수 핵심

| 변수 | 서비스 | 설명 |
|------|--------|------|
| `GAK_POSTGRES_APP_USER` | core-api | DML 계정 (기본: gak_app) |
| `GAK_POSTGRES_ADMIN_USER` | core-api | DDL·Flyway 계정 (기본: gak_admin) |
| `GAK_COOKIE_SECRET` | collector | HMAC-SHA256 서명 키 |
| `GAK_CHZZK_CLIENT_ID` | collector | OAuth 앱 Client ID |
| `GAK_CHZZK_CLIENT_SECRET` | collector | OAuth 앱 Client Secret |
| `gak.cookie.secure` | collector | prod=true, dev=false |

---

## 13. 문서 목록

| 문서 | 내용 |
|------|------|
| [00_PROJECT_MASTER_HISTORY.md](00_PROJECT_MASTER_HISTORY.md) | 문서 인덱스·변경 이력 |
| [01_ADR.md](01_ADR.md) | 아키텍처 결정 기록 |
| [02_troubleshooting.md](02_troubleshooting.md) | 트러블슈팅 통합 가이드 |
| [03_run_guide.md](03_run_guide.md) | 실행 순서·운영 체크 |
| [05_developer_handover.md](05_developer_handover.md) | 핸드오버 문서 |
| [22_security_hardening.md](22_security_hardening.md) | 보안 강화 작업 기록 |
| [26_vod_highlight_sequence_diagrams.md](26_vod_highlight_sequence_diagrams.md) | VOD 단계별 시퀀스 다이어그램 |
| [27_erd.md](27_erd.md) | ERD |
| [28_normalization_analysis.md](28_normalization_analysis.md) | 정규화 분석 |
