# 30. 시스템 신뢰성 설계

> 기능 소개가 아닌, 재시도·보안·DB 정합성·운영 관측성 관점에서 이 시스템이 어떻게 안정성을 확보했는지를 기술한다.  
> 구현 기준: 2026-05-17

---

## 목차

1. [재시도 및 장애 복구](#1-재시도-및-장애-복구)
2. [보안 설계](#2-보안-설계)
3. [DB 정합성 보장](#3-db-정합성-보장)
4. [LLM 신뢰 경계 관리](#4-llm-신뢰-경계-관리)
5. [운영 관측성](#5-운영-관측성)
6. [개선 전후 비교](#6-개선-전후-비교)

---

## 1. 재시도 및 장애 복구

### 1-1. VOD 채팅 크롤러 — 청크 단위 재시도

Chzzk API는 간헐적 429(Rate Limit)와 타임아웃이 발생한다. 크롤러는 청크 단위로 재시도하며, 전체 수집을 처음부터 재시작하지 않는다.

```
MAX_RETRIES = 2
REQUEST_TIMEOUT = 12s
재시도 대기: delay(retryCount + 1s)  →  1회 실패: 1s 대기, 2회 실패: 2s 대기
```

| 재시도 횟수 | 누적 최대 대기 |
|------------|-------------|
| 1회 | 1s |
| 2회 | 3s |
| 초과 시 | markFailed() — 해당 청크부터 중단, 이전 수집분 보존 |

**설계 포인트**: 재시도 초과 시 전체 VOD가 아닌 해당 청크부터 실패 처리해 이미 수집·발행된 채팅은 보존한다. `visitedCursors` Set으로 cursor 반복을 감지해 무한 루프를 사전 차단한다.

---

### 1-2. Resilience4j Retry — Chzzk 세션 갱신

Chzzk NID 세션은 외부 API에 의존하므로 간헐적 실패에 대비한 재시도가 설정되어 있다.

```yaml
resilience4j:
  retry:
    instances:
      chzzkSession:
        max-attempts: 5
        wait-duration: 5s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
```

| 시도 | 대기 |
|------|------|
| 1회 실패 | 5s |
| 2회 실패 | 10s |
| 3회 실패 | 20s |
| 4회 실패 | 40s |
| 5회 실패 | 최종 실패 처리 |

총 최대 대기: **75s**

---

### 1-3. Resilience4j Circuit Breaker — Ollama LLM

LLM 호출(`analyzeBatch`)에 Circuit Breaker를 적용해 Ollama 장애 시 파이프라인 전체가 블로킹되지 않도록 한다.

```java
@CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
public Mono<List<AnalyzedChatMessage>> analyzeBatch(...) { ... }

public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(..., Throwable t) {
    // LLM 장애 시 전체 배치를 NEUTRAL로 대체 — 파이프라인 중단 없음
    return Mono.just(createFallbackMessages(chats));
}
```

Circuit Breaker 발동 시 해당 배치를 전부 `NEUTRAL` 감정으로 처리한다. 채팅 저장은 유지되고 감정 점수만 기본값으로 채워진다. 라이브 방송 분석은 계속 진행된다.

---

### 1-4. VOD 분석 finalizer — 데이터 도달 대기

크롤러(collector)와 분석기(analyzer)는 Kafka로 통신한다. `vod-crawl-complete-topic`이 도착할 때 채팅 청크가 아직 Kafka 전파 중일 수 있으므로, finalizer는 조용한 기간이 확보될 때까지 대기한다.

```
FINALIZE_QUIET_PERIOD  = 1200ms  (집계에 새 채팅이 없는 시간)
FINALIZE_RETRY_DELAY   = 600ms   (조건 미충족 시 재확인 간격)
MAX_FINALIZE_RETRIES   = 12      (최대 재시도)
```

| 시나리오 | 동작 |
|----------|------|
| 청크가 아직 도착 중 | 600ms마다 재확인, 최대 12회 (7.2s) |
| 12회 초과 후에도 집계 없음 | `publishFailure()` — 원인 로그 포함 |
| 채팅 수집 자체가 0건 | `publishCompletion(0, 0)` — 빈 결과로 정상 완료 처리 |

---

### 1-5. ANALYZING stuck 자동 보정

VOD 분석 상태는 collector의 in-memory 상태 머신에 저장된다. 재기동 시 초기화되므로, `ANALYZING`이 영구적으로 남을 수 있다. 이를 두 가지 경로로 자동 보정한다.

```
STALE_ANALYZING_TIMEOUT = 30분
```

**보정 흐름**:

```
GET /api/v1/vod/{videoNo}/status 폴링
  └─ status == "ANALYZING"
       ├─ 경과 시간 < 30분: 그대로 반환
       └─ 경과 시간 ≥ 30분 또는 IDLE
            ├─ core-api에 highlights 존재 확인
            │    ├─ 존재: markCompleted() → COMPLETED로 교정
            │    └─ 미존재 + timeout: markFailed() → FAILED로 교정
            └─ core-api 조회 실패: 기존 상태 그대로 반환 (데이터 손실 방지)
```

---

### 1-6. VOD 동시성 슬롯 TTL

Redis 슬롯 카운터는 분석 완료/실패 Kafka 이벤트 수신 시 반납된다. 이벤트가 유실될 경우를 대비해 TTL을 설정한다.

```
SLOT_TTL = 30분 (슬롯 획득 시점 기준)
키: vod:active:user:{ownerId}, vod:active:global, vod:owner:{videoNo}
```

TTL 만료 시 슬롯이 자동 해제되어 다음 분석 요청이 수락된다.

---

## 2. 보안 설계

### 2-1. 인증 토큰 구조

```
base64url(channelId . sessionId . expiresAt) + "." + HMAC-SHA256(위 내용, GAK_COOKIE_SECRET)
```

서명 키(`GAK_COOKIE_SECRET`)는 환경변수로만 주입되며, 코드에 하드코딩되지 않는다.

### 2-2. 3중 검증 흐름

모든 보호 경로는 단일 조건이 아닌 3단계를 순차 통과해야 한다.

```
요청 도착
  ① GAK_OWNER_ASSERTION 쿠키 HMAC 서명 검증
     실패 → 401 (서명 위조 또는 만료)
  ② Redis GET gak:owner-session:{channelId}
     키 없음 또는 세션 불일치 → 401 (로그아웃 또는 탈취 토큰)
  ③ URL channelId == 토큰 내 channelId
     불일치 → 403 (타인 채널 접근 시도)
```

①②③ 각 단계가 독립적으로 차단된다. Redis 세션 바인딩(②)이 있어 로그아웃 후 탈취된 토큰도 즉시 무효화된다.

### 2-3. IDOR 취약점 제거

**이전**: `OwnerIdentityResolver`가 쿠키 파싱 실패 시 `X-Chzzk-Owner-Id` 헤더나 쿼리 파라미터로 폴백했다.  
**문제**: 임의 헤더로 타인 채널 ID를 주입하면 해당 채널의 데이터를 조회·수정 가능 (IDOR).  
**현재**: 쿠키 경로만 허용, 헤더·쿼리 폴백 완전 제거.

### 2-4. 내부 API 경로 은닉

`InternalAccessFilter`는 `X-Internal-Secret` 헤더 불일치 시 **404**를 반환한다. 403이 아닌 이유는 경로의 존재 자체를 외부에 노출하지 않기 위해서다.

```
/internal/** 경로
  X-Internal-Secret 일치 → 정상 처리
  불일치 또는 없음 → 404 (경로 없는 것처럼 응답)
```

### 2-5. OAuth CSRF 방지

```
state 파라미터 → Redis SET gak:auth:state:{state} TTL=10분
콜백 수신 시 state 대조 후 즉시 DELETE
```

TTL이 지나면 state가 자동 만료되어 재사용 불가. 콜백 처리 후 즉시 삭제해 replaying을 차단한다.

### 2-6. 쿠키 보안 플래그

| 플래그 | 값 | 효과 |
|--------|-----|------|
| `HttpOnly` | true | XSS로 JavaScript에서 쿠키 읽기 불가 |
| `Secure` | 환경변수 `GAK_COOKIE_SECURE` | 프로덕션=true → HTTPS 전송만 허용 |
| `SameSite` | Lax | CSRF 기본 방어 |

---

## 3. DB 정합성 보장

### 3-1. 중복 방지 제약

```sql
-- analyzed_chats: 동일 채팅 메시지 중복 저장 방지
message_id VARCHAR(255) UNIQUE NOT NULL

-- user_vod_library: 사용자당 동일 VOD 항목 1개 유지
CONSTRAINT uk_user_vod_library_owner_video UNIQUE (owner_id, video_no)
```

`analyzed_chats`에서 `message_id` UNIQUE는 Kafka consumer가 동일 메시지를 재처리하더라도(at-least-once 보장) DB에 중복이 저장되지 않도록 보호한다. `user_vod_library`는 `upsert` 패턴으로 항상 최신 상태를 유지한다.

### 3-2. 재분석 전 클린 슬레이트

분석 재시작 시 이전 결과가 섞이지 않도록 삭제 후 시작한다.

```java
// VodController.doTriggerAnalysis()
vodHighlightRepository.deleteAllByVideoNo(videoNo)   // 기존 하이라이트 삭제
  .then(vodTimelinePointRepository.deleteAllByVideoNo(videoNo))  // 기존 타임라인 삭제
  .then(collectorWebClient.post()...)                // 크롤링 시작
```

삭제 실패는 `onErrorResume`으로 넘어가지 않고 로그 후 계속 진행(`continuing anyway`)한다. 불완전한 이전 데이터가 남더라도 새 분석이 덮어쓰므로 일시적 정합성 문제에 그친다.

### 3-3. 스키마 버전 관리

Flyway 7개 마이그레이션으로 스키마를 관리한다. `schema.sql` 수동 반영은 사용하지 않는다.

| 마이그레이션 | 변경 내용 |
|-------------|-----------|
| V1 | 기본 스키마 (`analyzed_chats`, `highlight_records`, `vod_highlights`) |
| V2 | `vod_timeline_points` 추가 |
| V3 | `vod_highlights`에 편집 점수 컬럼 추가 (`intensity/transition/editability_score`) |
| V4 | `user_vod_library`, `user_vod_activity` 추가 |
| V5 | `vod_highlights.scene_label` 추가 |
| V6 | `vod_highlights` 신호 비율 컬럼 추가 (6개) |
| V7 | pgvector 익스텐션, `embedding`, `embedding_text` 컬럼, ivfflat 인덱스 추가 |

DDL은 `gak_admin` 계정(Flyway 전용), DML은 `gak_app` 계정(R2DBC 런타임)으로 분리해 런타임이 스키마를 변경할 수 없도록 권한을 분리한다.

### 3-4. R2DBC 환경에서 참조 무결성

FK 제약을 DB 레벨에 선언하지 않는 대신 애플리케이션 레이어에서 보장한다.

- **재분석 전 deleteAll**: 하이라이트가 없는데 activity 참조가 남는 상황 방지
- **upsert 패턴**: `user_vod_library`는 항상 먼저 upsert 후 activity 기록
- **onErrorResume 격리**: 라이브러리 동기화 실패가 주요 응답을 막지 않도록 격리

---

## 4. LLM 신뢰 경계 관리

LLM(Ollama)은 신뢰할 수 없는 외부 시스템 경계로 취급한다. 입출력 전 구간에 강제 검증을 적용해 파이프라인이 LLM 품질에 종속되지 않도록 한다.

### 4-1. 입력 가드레일 (감정 분석 배치)

```
① 빈 채팅 제거
② MAX_BATCH_SIZE = 30  (초과분은 버림)
③ MAX_INPUT_CHARS = 3000  (문자 수 상한 초과 시 해당 채팅부터 버림)
```

가드레일이 발동하면 `gak.llm.batch.capped` 메트릭이 증가해 운영 중 확인 가능하다.

**동적 타임아웃**: 고정값 대신 배치 크기에 비례해 산정한다.

```
timeout = min(90s, 20s + batchSize × 1.5s)
```

| 배치 크기 | 타임아웃 |
|-----------|---------|
| 5개 | 27.5s |
| 15개 | 42.5s |
| 30개 (상한) | 65s |

고정 60s였다면 소량 배치도 60s를 기다리거나, 대량 배치가 조기 타임아웃될 수 있다.

### 4-2. 출력 가드레일 (감정 분석 결과)

```
① 7가지 감정 키 완결성 검증 (누락 키는 0.0으로 채움)
② 각 점수 [0.0, 1.0] 클램핑
③ 합계 < 0.001 → NEUTRAL로 전체 교정 (의미 없는 응답 방지)
④ 예상 외 키 포함 시 경고 로그 기록
```

`gak.llm.output.zeroed` 메트릭으로 교정 빈도를 추적한다.

### 4-3. 동시성 가드레일 (Semaphore)

```java
private final Semaphore llmSlot = new Semaphore(1);

if (!llmSlot.tryAcquire()) {
    recordCount("gak.llm.batch.skipped");   // 관측 가능한 스킵
    return Mono.just(List.of());
}
return doAnalyzeBatch(capped).doFinally(ignored -> llmSlot.release());
```

LLM 호출을 한 번에 하나로 직렬화한다. 이미 처리 중일 때 들어온 배치는 스킵되고 `gak.llm.batch.skipped` 메트릭에 기록된다.

### 4-4. 하이라이트 LLM 리뷰 — 거절 시 완전 제거 아닌 점수 하향

LLM이 하이라이트 후보를 거절해도 목록에서 즉시 제거하지 않는다.

```
LLM 승인: totalScore = (score + 2.4) × (1 + (intensity - 5) × 0.05)
LLM 거절: totalScore = score × 0.38    (hardRejected = true)
```

거절된 후보는 점수가 대폭 낮아지지만 `MIN_HIGHLIGHTS` 조건 충족을 위한 fallback pool에 남는다. LLM이 모든 후보를 거절해도 최소 5개는 선별된다.

### 4-5. 하이라이트 LLM 타임아웃 처리

```
LLM 리뷰 전체 timeout = 4분 (LLM_REVIEW_TIMEOUT)
개별 후보 per-request timeout = 45초
```

4분 타임아웃 초과 시 `TimeoutException`을 감지해 **LLM 결과 없이 휴리스틱 점수만으로** 선별을 완료한다. 분석 자체는 실패하지 않는다.

---

## 5. 운영 관측성

### 5-1. 메트릭 목록

| 메트릭 | 의미 |
|--------|------|
| `gak.llm.api.calls.total` | LLM 호출 총 횟수 |
| `gak.llm.api.latency` | LLM 호출 응답 시간 (Timer) |
| `gak.llm.batch.skipped` | 동시성 가드레일로 스킵된 배치 수 |
| `gak.llm.batch.capped` | 입력 상한으로 잘린 배치 수 |
| `gak.llm.output.zeroed` | 출력 합계 0 → NEUTRAL 교정 횟수 |

모두 `MeterRegistry`(Micrometer)를 통해 기록되며 Prometheus/Grafana 연동 가능하다.

### 5-2. 로그 구조

모든 서비스가 `[ServiceName]` 접두사를 포함한 구조화 로그를 사용한다.

```
[VOD-Crawler] Starting full chat crawl for videoNo=12345678
[VOD-Crawler] Progress videoNo=12345678, pages=10, chats=1523, nextCursor=...
[VOD-Crawler] Reached end of VOD chats for videoNo=12345678, pages=47, chats=7842
[Vod-Analyzer] Finalized videoNo=12345678, pages=47, chats=7842, windows=261, highlights=12
[VOD-Highlight-Consumer] Saved+embedded: videoNo=12345678, time=1230s
```

크롤링 진행 상황은 1페이지와 10페이지마다 로그를 남겨 불필요한 로그 폭발을 방지한다.

### 5-3. 분석 상태 중간 갱신

`VodAnalysisStatusService`는 크롤링 중 실시간으로 상태를 갱신한다.

```
REQUESTED  →  CRAWLING (pages=N, chats=M 갱신)  →  ANALYZING  →  COMPLETED / FAILED
```

폴링 응답에 `pagesProcessed`, `chatsCollected`, `message` 필드가 포함되어 프론트엔드가 진행 상황을 표시할 수 있다. 상태는 in-memory이므로 재기동 시 IDLE로 초기화된다.

### 5-4. fail-open vs fail-secure 의식적 분리

| 시스템 | Redis 장애 시 | 근거 |
|--------|-------------|------|
| `OwnerAccessFilter` 세션 검증 | **fail-secure** — 401 반환 | 인증 실패는 보안 문제. 불확실한 상태에서 접근 허용 불가 |
| `VodAnalysisSlotService` 슬롯 카운터 | **fail-open** — 분석 허용 | 슬롯 카운트가 틀려도 서비스 중단보다 낫다. 최악의 경우 동시 분석 수가 일시적으로 3을 초과 |

같은 Redis 의존이지만 "무엇을 지키느냐"에 따라 전략이 다르다.

---

## 6. 개선 전후 비교

### 6-1. SSE 구독 전 메시지 유실

| 항목 | 이전 (`Sinks.multicast()`) | 이후 (`Sinks.replay(100)`) |
|------|--------------------------|--------------------------|
| 구독 전 메시지 | 전량 유실 (0% 보존) | 최대 100개 버퍼 보존 |
| 신규 구독자 | 구독 시점 이후 메시지만 수신 | 버퍼에 쌓인 최신 100개 즉시 전달 |
| 문제 발생 시점 | 채팅이 빠른 방송에서 탭을 새로 열면 최초 감정 그래프가 비어 보임 | — |

---

### 6-2. LLM 스킵 관측 가능성

| 항목 | 이전 (`AtomicBoolean`) | 이후 (`Semaphore + metric`) |
|------|----------------------|--------------------------|
| LLM 처리 중 새 배치 도착 | 조용히 버림 — 로그·메트릭 없음 | `gak.llm.batch.skipped` 카운터 증가 |
| 운영 중 스킵 확인 | 불가능 | Micrometer → Prometheus/Grafana 연동 가능 |
| 향후 병렬 확장 | `AtomicBoolean` 교체 필요 | `Semaphore(N)`으로 상수 변경만으로 가능 |

---

### 6-3. IDOR 취약점 — 헤더 폴백 제거

| 항목 | 이전 | 이후 |
|------|------|------|
| 인증 경로 | 쿠키 파싱 실패 → `X-Chzzk-Owner-Id` 헤더 폴백 → 쿼리 파라미터 폴백 | 쿠키 단일 경로, 폴백 없음 |
| 공격 시나리오 | `curl -H "X-Chzzk-Owner-Id: {타인채널ID}" ...` → 타인 채널 접근 | 헤더 무시, 쿠키 없으면 401 |
| 방어 층 | 서명 검증만 | 서명 + Redis 세션 + channelId 소유권 3중 검증 |

---

### 6-4. VOD 하이라이트 앞쪽 쏠림

| 항목 | 이전 (전역 상위 N개 선택) | 이후 (버킷 분산 선택) |
|------|------------------------|--------------------|
| 선별 방식 | 전체 점수 상위 N개 | 시간대 버킷별 대표 1개 우선 확보 → 남은 쿼터를 전역 상위로 채움 |
| 버킷 수 | — | `min(targetCount, min(8, max(4, targetCount/2)))` |
| 결과 | 초반 채팅 밀도가 높은 VOD에서 하이라이트가 앞 20~30%에 집중 | 전체 구간에 걸쳐 분산 |
| 최소·최대 | — | MIN=5, MAX=24 (`windows.size() × 0.12` 기준) |

---

### 6-5. LLM 배치 타임아웃 — 고정값 → 부하 비례

| 항목 | 고정 타임아웃 | 동적 타임아웃 |
|------|-------------|------------|
| 공식 | 60s 고정 | `min(90s, 20s + batchSize × 1.5s)` |
| 5개 배치 | 60s 대기 | 27.5s — 더 빠른 타임아웃 감지 |
| 30개 배치 | 60s 대기 (부족) | 65s — 실제 처리 시간에 맞게 여유 확보 |
| 효과 | 소량 배치에서 불필요하게 길거나, 대량 배치에서 조기 타임아웃 | 배치 크기에 비례한 적정 허용 시간 |

---

### 6-6. DB 계정 — 권한 최소화

| 항목 | 이전 (`gak_user` / `neul_user`) | 이후 (`gak_admin` / `gak_app`) |
|------|--------------------------------|-------------------------------|
| 계정 구조 | 단일 계정이 DDL·DML 모두 수행 | DDL 전용(`gak_admin`) + DML 전용(`gak_app`) |
| Flyway 실행 계정 | 단일 계정 | `gak_admin` — DDL 권한만 |
| R2DBC 런타임 계정 | 단일 계정 | `gak_app` — SELECT/INSERT/UPDATE/DELETE만 |
| 위험 | 런타임 SQL 인젝션 성공 시 스키마 변경 가능 | 런타임 계정이 DDL 불가 |
