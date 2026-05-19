---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'Pretendard', 'Apple SD Gothic Neo', sans-serif;
    background: #0a0a0a;
    color: #ffffff;
    padding: 48px 56px;
  }
  h1 { color: #00FFA3; font-size: 2rem; margin-bottom: 0.4em; }
  h2 { color: #00FFA3; font-size: 1.5rem; border-bottom: 1px solid #222; padding-bottom: 0.3em; }
  h3 { color: #aaaaaa; font-size: 1rem; font-weight: 600; letter-spacing: 0.1em; text-transform: uppercase; }
  code { background: #1a1a1a; color: #00FFA3; padding: 2px 6px; border-radius: 4px; font-size: 0.85em; }
  pre { background: #111; border: 1px solid #222; border-radius: 8px; padding: 0.8em 1em; }
  pre code { background: none; padding: 0; color: #e0e0e0; font-size: 0.78em; line-height: 1.55; }
  table { width: 100%; border-collapse: collapse; font-size: 0.85em; }
  th { background: #1a1a1a; color: #00FFA3; padding: 8px 12px; text-align: left; }
  td { padding: 8px 12px; border-bottom: 1px solid #1e1e1e; }
  strong { color: #00FFA3; }
  blockquote { border-left: 3px solid #333; padding-left: 1em; color: #888; font-size: 0.85em; margin-top: 0.5em; }
  .columns { display: grid; grid-template-columns: 1fr 1fr; gap: 2em; }
---

<!-- 1. 표지 -->

# 문제 해결 — 두 가지 버그
## 증상이 아닌 원인을 찾는 과정

<br>

**① 하이라이트가 앞쪽 시간대로만 몰린다**  
— 점수 로직 문제처럼 보였지만, 선별 알고리즘 구조의 문제였다

<br>

**② VOD 분석 상태가 ANALYZING에서 안 넘어간다**  
— 이벤트가 유실된 것처럼 보였지만, 인메모리 상태와 실제 결과 간 불일치였다

---

<!-- 2. 문제 ① — 하이라이트 시간대 편중 -->

## 하이라이트 시간대 편중

```
  VOD 타임라인  (2시간 방송)

  0분 ──────────────────────────────────────────── 120분
  │
  ▼  점수 기준 상위 5개 선택 결과

  ████ ████ ████ ██░░ ░░░░ ░░░░ ░░░░ ░░░░ ░░░░ ░░░░
   1위  2위  3위  4위  (이후 구간은 선택 안 됨)

  실제로 뒷부분에도 반응이 있었지만,
  앞쪽 고밀도 구간이 점수에서 계속 이겼다.
```

<br>

**처음 가설: 점수 계산이 잘못됐다**  
→ 점수 공식을 여러 번 바꿔봤지만 결과가 달라지지 않았다

**실제 원인: 선별 방식이 잘못됐다**  
→ 상위 k개를 고르면 자연히 밀집된 구간이 독점한다

---

<!-- 3. 왜 앞쪽이 유리한가 -->

## 초반 편중 원인

```
  시청자 수 추이 (일반적인 VOD 패턴)

  ▲ 채팅 밀도
  │
  █████
  ██████████
  ████████████████
  ████████████████████▓▓▓▓▓▒▒▒▒░░░░
  │
  0분                               120분

  ─ 방송 초반에 시청자가 가장 많다
  ─ 채팅 밀도 자체가 앞쪽에서 높다
  ─ density score, user score 모두 앞쪽이 유리
```

<br>

> 점수 공식이 틀린 게 아니라, **"채널의 평균적 패턴"을 점수가 반영**하고 있었다.  
> 편중은 점수의 결함이 아니라 **선별 구조의 결함**이었다.

---

<!-- 4. 해결 ① — 버킷 기반 분산 선택 -->

## 해결 ① — 버킷 기반 분산 선택

```
  VOD 전체를 N개 구간(bucket)으로 나눈다
  각 구간에서 1위를 먼저 확보한 뒤, 남은 자리를 전역 상위로 채운다

  targetCount = 5,  bucketCount = 4

  [ 0~30분 ] [ 30~60분 ] [ 60~90분 ] [ 90~120분 ]
      ↓           ↓           ↓            ↓
    1위 확보    1위 확보    1위 확보     1위 확보    ← 버킷 대표 (4개)

  globalQuota = targetCount - bucketCount = 1
      ↓
  전체 eligible 중 아직 선택 안 된 1위 추가             ← 전역 상위 (1개)

  ──────────────────────────────────────────────
  결과: 시간대 분산 보장 + 전역 상위도 포함
```

> `bucketCount = min(targetCount, min(8, max(4, targetCount / 2)))`  
> 하이라이트 수에 따라 버킷 수가 자동 조정된다. 과도한 분산도 방지.

---

<!-- 5. 해결 ② — transitionScore -->

## 해결 ② — transitionScore로 "진짜 순간" 포착

버킷 분산만으론 부족했다. 버킷 내에서도 **맨 앞 고밀도 구간**이 이겼다.

```
  transitionScore: 이전 구간이 조용했는데 현재 구간이 급등했는가

  previous  →  current  →  next
  (조용)        (급등)       (유지)

  ─ quietBaseline:   previous < 평균 × 0.65  이면 1.0, 아니면 0.0
  ─ burstFromQuiet:  quietBaseline × (messageJump - 1.0) × 2.2
  ─ userSurge:       quietBaseline × (userJump  - 1.0) × 1.6
  ─ sustainedBonus:  next도 평균 이상이면 +2.5
  ─ 상한: 7.0
```

<br>

```
  totalScore = (intensity×0.55 + transition×0.20 + editability×0.25)
                × edgePenalty × negativePenalty
```

> 줄곧 바쁜 구간(intensity 높음)보다  
> **조용하다가 갑자기 터진 구간**(transition 높음)을 별도로 보상한다.

---

<!-- 6. 문제 ② — ANALYZING stuck -->

## ANALYZING 상태 고착

```
  정상 흐름

  collector          analyzer            core-api
     │                  │                   │
     │── crawl 완료 ──▶  │                   │
     │   markAnalyzing   │── 분석 완료 ──▶   │
     │                   │   Kafka 이벤트    │── highlights 저장
     │◀──────────────────│                   │
     │   vod-analysis-   │                   │
     │   complete-topic  │                   │
     │                   │                   │
     │  markCompleted    │                   │
```

```
  문제 상황

  collector          analyzer            core-api
     │                  │                   │
     │── crawl 완료 ──▶  │                   │
     │   markAnalyzing   │── 분석 완료 ──▶   │
     │                   │   Kafka 이벤트    │── highlights 저장
     │                   │                   │
     │   ✗ 이벤트 미수신  │                   │
     │   (consumer 재시작, 타이밍 불일치)     │
     │                   │                   │
     │  ANALYZING 상태 유지... 영구          │
```

---

<!-- 7. 원인 — 인메모리 상태와 실제 결과의 분리 -->

## 상태·결과 불일치

```
  collector 서비스

  VodAnalysisStatusService
  ConcurrentHashMap<videoNo, status>
       ↑
  markAnalyzing()  ←  crawl 완료 시 직접 호출
  markCompleted()  ←  Kafka 이벤트 수신 시 호출  ← 여기가 끊기면 stuck

  ─────────────────────────────────────────────────

  core-api 서비스

  vod_highlights 테이블  ←  Kafka(vod-analyzed-topic)으로 저장 완료
```

<br>

> 두 서비스는 독립적으로 동작한다.  
> Kafka 이벤트가 한 번 유실되면 **상태는 ANALYZING, 결과는 DB에 존재**하는 불일치가 생긴다.  
> collector를 재시작하면 인메모리 상태 자체가 사라진다.

---

<!-- 8. 해결 — 조회 시점에 보정 -->

## 조회 시점 상태 보정

```
  GET /api/v1/vod/{videoNo}/status

  현재 상태 == ANALYZING ?
       │
       ├── startedAt 기준 30분 이상 경과?  ─── YES ──▶  보정 조회 + 초과 시 FAILED
       │
       └── 아직 30분 미만?  ─────────────────────────▶  보정 조회

  보정 조회: core-api GET /highlights 에 데이터가 있는가?
       │
       ├── highlights 존재  ──▶  markCompleted()  ──▶  COMPLETED 반환
       │                         (상태 인메모리 갱신)
       │
       └── highlights 없음  ──▶  현재 상태 그대로 반환
           + 30분 초과이면  ──▶  markFailed()
```

> 이벤트 전달 경로를 고치지 않았다.  
> **조회 시 실제 결과를 확인해 상태를 맞추는 것**이 더 단순하고 멱등하다.

---

<!-- 9. 두 문제에서 얻은 설계 관점 -->

## 두 문제에서 얻은 관점

| | 하이라이트 편중 | ANALYZING stuck |
|---|---|---|
| **표면 증상** | 뒷부분 하이라이트가 안 나옴 | 상태가 안 넘어감 |
| **처음 가설** | 점수 공식이 잘못됐다 | 이벤트가 유실됐다 |
| **실제 원인** | 선별 구조가 잘못됐다 | 상태·결과가 분리돼 있다 |
| **해결 방향** | 알고리즘 재설계 | 조회 시점에 보정 |

<br>

**공통점**

- 증상을 따라가면 잘못된 곳을 고치게 된다
- 분산 시스템에서 "상태"와 "실제 결과"는 따로 관리된다
- 복잡한 수정보다 **단순하고 멱등한 보정**이 안전하다
