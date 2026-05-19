# 14. VOD 동시성 제어 설계

> 작성일: 2026-03-31 / 구현 완료: 2026-05-02  
> 구현 상세: [18_llm_guardrail_plan.md](18_llm_guardrail_plan.md) §3-5

---

## 배경

VOD 분석은 "분석 시작" 버튼 클릭 즉시 비동기로 시작되며, 완료까지 수 분이 소요된다. 내부 흐름은 다음과 같다.

```
사용자 요청
  → collector: VOD 채팅 크롤링 (수백~수천 페이지)
  → Kafka: 청크 단위 전송
  → analyzer: 30초 윈도우 집계 + Ollama LLM 리뷰 (상위 12개 후보)
  → core-api: DB 저장 + SSE 알림
```

하나의 분석 작업이 Ollama를 수 분간 점유한다. 동시 분석 수 제한 없이 여러 요청이 쌓이면:

- Ollama LLM 큐 적체 → 분석 시간 예측 불가
- collector 메모리·Kafka lag 선형 증가
- 동시 사용자 모두 기아 상태(starvation)

---

## 의사결정: 동시성 제어 방식

### 고려한 선택지

| 방식 | 설명 | 판단 |
|------|------|------|
| in-memory ConcurrentHashMap | 구현 간단 | 기각 — 수평 확장 시 각 인스턴스가 개별 카운터 보유 → 제한 무력화 |
| DB 기반 상태 조회 | 정확 | 기각 — 요청 진입 시점에 즉각 판단 필요, DB 조회 지연 허용 불가 |
| **Redis 카운터 + TTL** | 분산 환경 지원, TTL로 stuck 자동 만료 | **채택** |
| 대기열(Queue) | 거절 없이 순차 처리 | 추후 고려 — 현재는 즉시 거절이 UX상 명확 |

### 결정

**Redis 카운터(사용자별 1건, 전역 3건) + TTL 30분** 채택.

Redis 장애 시 `fail-open`: 분석을 허용하고 슬롯 카운터만 갱신하지 않는다. 인증(fail-secure)과 반대 전략 — 분석 차단보다 분석 허용이 사용자에게 덜 해롭다고 판단.

---

## 구현된 구조

### Redis 키 설계

```
vod:active:global          → 전체 동시 분석 수 (상한 3)
vod:active:user:{ownerId}  → 사용자별 동시 분석 수 (상한 1)
vod:owner:{videoNo}        → ownerId 저장 (슬롯 반납 시 역매핑)
```

모든 키 TTL = 30분 → analyzer 크래시로 완료 이벤트가 오지 않아도 자동 해제.

### 슬롯 생애주기

```
POST /vod/{videoNo}/analyze
  └─ VodAnalysisSlotService.tryAcquire(ownerId, videoNo)
        ├─ REJECTED_USER (429)   → 이미 분석 중인 VOD 있음
        ├─ REJECTED_GLOBAL (503) → 시스템 전체 슬롯 소진
        └─ ACQUIRED
              └─ 분석 파이프라인 시작
                    └─ Kafka: vod-analysis-complete-topic 또는 vod-analysis-failed-topic
                          └─ VodAnalysisEventConsumer.releaseByVideoNo(videoNo)
```

### HTTP 응답 코드 선택 근거

| 상태 | 코드 | 이유 |
|------|------|------|
| REJECTED_USER | 429 Too Many Requests | 사용자가 발생시킨 제한 |
| REJECTED_GLOBAL | 503 Service Unavailable | 시스템 자원 소진, 사용자 귀책 아님 |

### 영향 클래스

```
core-api/domain/chat/service/VodAnalysisSlotService.java    ← 슬롯 획득/반납
core-api/domain/chat/service/VodAnalysisEventConsumer.java  ← 완료/실패 시 반납
core-api/domain/chat/controller/VodController.java          ← 429/503 응답 처리
```

---

## VOD 상태 머신

```
IDLE → REQUESTED → CRAWLING → ANALYZING → COMPLETED
                                        ↘ FAILED
```

| 상태 | 주체 | 전환 조건 |
|------|------|----------|
| IDLE | — | 초기 상태 |
| REQUESTED | collector | 분석 시작 요청 수신 |
| CRAWLING | collector | 첫 번째 청크 수집 시작 |
| ANALYZING | analyzer | VOD 크롤 완료, 분석 중 |
| COMPLETED | collector | vod-analysis-complete-topic 수신 or highlight fallback |
| FAILED | collector | vod-analysis-failed-topic 수신 or 30분 타임아웃 |

**상태는 collector의 in-memory(`ConcurrentHashMap`)에 저장된다.** 재시작 시 IDLE로 초기화된다. 이로 인한 stuck 상태는 highlight 존재 여부 fallback으로 자동 복구된다 — 상세: [19_status_polling_plan.md](19_status_polling_plan.md).

---

## 알려진 한계 및 향후 고려사항

| 항목 | 현황 | 개선 방향 |
|------|------|----------|
| MAX_GLOBAL 값 | 보수적으로 3 설정 | Ollama 서버 스펙 실측 후 조정 |
| 대기열(QUEUED 상태) | 미구현, 즉시 거절 | 트래픽 증가 시 도입 검토 |
| StatusService 영속화 | in-memory (재시작 시 소실) | 잦은 재시작 환경에서는 Redis 저장으로 전환 |
| LLM 슬롯 공유 | 실시간 채팅 Slow-Path와 VOD가 동일 Ollama 사용 | 부하 실측 후 Semaphore 슬롯 수 조정 |
