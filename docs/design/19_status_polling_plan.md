# VOD 분석 상태 누락 케이스 및 폴링 정책 개선 기록

작성일: 2026-05-02

## 1. 배경 및 문제 상황

### 1-1. completion 이벤트 누락 케이스

`GET /status`의 ANALYZING → COMPLETED 하이라이트 폴백은 이미 존재했으나 두 가지 케이스를 커버하지 못했다.

**케이스 A: analyzer 프로세스 크래시 (하이라이트 미저장)**

- Kafka `vod-analysis-complete-topic` 이벤트가 발행되지 않음
- 하이라이트가 DB에 없으면 폴백 조건도 충족 안 됨 → status가 ANALYZING에 영구 고착

**케이스 B: collector 재시작**

- `VodAnalysisStatusService`는 in-memory ConcurrentHashMap → 재시작 시 소멸
- status가 IDLE로 초기화되면 ANALYZING 폴백 경로가 실행되지 않음
- 분석이 재시작 이후 완료되더라도 프론트엔드는 IDLE을 보고 폴링을 멈춤

### 1-2. 폴링 간격 고정

프론트엔드는 REQUESTED / CRAWLING / ANALYZING 모든 상태에서 동일하게 5초마다 폴링했다.

- REQUESTED: 크롤 시작 여부를 빠르게 확인해야 하므로 5초는 느림
- ANALYZING: LLM 분석은 수 분 소요 — 5초 폴링은 불필요하게 잦음

---

## 2. 고려한 대안

### completion 이벤트 누락

| 옵션 | 설명 | 선택 여부 |
|---|---|---|
| StatusService 영속화 (Redis/DB) | 재시작 후에도 상태 보존 | 기각 — 구조 변경 비용 대비 in-memory로도 폴백으로 복구 가능 |
| IDLE 상태에서도 highlight 폴백 실행 | 재시작 복구 커버 | **채택** — 추가 인프라 없이 케이스 B 해결 |
| ANALYZING 타임아웃 → FAILED 전환 | 케이스 A 해결 | **채택** — 영구 고착 방지, 30분 임계값 |
| Kafka retry / DLQ | Kafka 레벨 재전송 | 기각 — 현 규모에서 운영 오버헤드 증가 |

### 폴링 간격

| 옵션 | 설명 | 선택 여부 |
|---|---|---|
| 고정 간격 유지 | 변경 없음 | 기각 — ANALYZING 단계에서 불필요한 요청 과다 |
| 상태별 고정값 | REQUESTED 3s / CRAWLING 5s / ANALYZING 8s | **채택** — 단순하고 예측 가능 |
| 지수 백오프 | 응답 지연 시 간격 증가 | 기각 — 복잡도 증가 대비 이득 불명확 |

---

## 3. 최종 결정 및 구현 내용

### 3-1. GET /status 폴백 통합 (`VodCollectorController`)

**변경 전**

```java
if (!"ANALYZING".equals(current.status())) {
    return Mono.just(current);
}
// highlight 조회 → COMPLETED 전환
```

**변경 후**

```java
if ("ANALYZING".equals(current.status())) {
    boolean timedOut = current.startedAt() != null
            && Duration.between(current.startedAt(), Instant.now())
               .compareTo(STALE_ANALYZING_TIMEOUT) >= 0;  // 30분
    return checkHighlightsFallback(videoNo, current, timedOut);
}

if ("IDLE".equals(current.status())) {
    return checkHighlightsFallback(videoNo, current, false);
}
```

`checkHighlightsFallback(videoNo, current, markFailedIfEmpty)`:
- highlight 존재 → `markCompleted()` (ANALYZING, IDLE 공통)
- highlight 없고 `markFailedIfEmpty=true` → `markFailed()` (ANALYZING timeout 전용)
- highlight 없고 `markFailedIfEmpty=false` → 원래 상태 유지 (정상 IDLE, 분석 진행 중)

### 3-2. 상태별 폴링 간격 (`useVodHighlightBoard.ts`)

```typescript
const pollInterval =
  status.status === "REQUESTED" ? 3000
  : status.status === "ANALYZING" ? 8000
  : 5000;  // CRAWLING, WAITING 기본값
```

`status.status`가 deps에 있으므로 상태 전환 시 interval이 자동 재설정된다.

---

## 4. 변경 파일 목록

| 파일 | 변경 유형 | 주요 내용 |
|---|---|---|
| `collector/.../VodCollectorController.java` | 수정 | IDLE 폴백 추가, ANALYZING timeout (30분), `checkHighlightsFallback` 메서드 추출 |
| `frontend/.../useVodHighlightBoard.ts` | 수정 | 상태별 폴링 간격: REQUESTED 3s / ANALYZING 8s / 그 외 5s |

---

## 5. 체크리스트 업데이트

`09_evolution_roadmap.md` 섹션 C 항목:

- [x] analyzer completion 이벤트 누락 케이스 더 줄이기
  - ANALYZING 30분 타임아웃 → FAILED 전환
  - IDLE 상태에서 highlight 존재 시 COMPLETED 복구
- [x] status polling 간격/정책 재검토
  - REQUESTED 3s / ANALYZING 8s / 그 외 5s

---

## 6. 향후 고려사항

- **StatusService 영속화**: 서비스 재시작 빈도가 높아지면 Redis로 상태를 저장해 IDLE 폴백 없이도 복구 가능하게 개선.
- **STALE_ANALYZING_TIMEOUT 조정**: 30분은 보수적 설정. Ollama 처리 시간 실측 후 단축 가능.
- **IDLE 폴백 비용**: IDLE 상태의 모든 `GET /status` 요청이 core-api highlight 조회를 추가로 발생시킴. 트래픽이 늘면 IDLE 폴백 조건에 `startedAt` 존재 여부 같은 힌트를 추가해 조회 빈도를 줄일 수 있다.
