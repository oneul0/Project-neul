---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'Pretendard', 'Apple SD Gothic Neo', sans-serif;
    background: #0a0a0a;
    color: #ffffff;
    padding: 44px 52px;
  }
  h1 { color: #00FFA3; font-size: 1.9rem; margin-bottom: 0.3em; }
  h2 { color: #00FFA3; font-size: 1.4rem; border-bottom: 1px solid #222; padding-bottom: 0.3em; margin-bottom: 0.5em; }
  h3 { color: #aaaaaa; font-size: 0.9rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; }
  code { background: #1a1a1a; color: #00FFA3; padding: 2px 6px; border-radius: 4px; font-size: 0.82em; }
  pre { background: #111; border: 1px solid #222; border-radius: 8px; padding: 0.75em 1em; margin: 0.4em 0; }
  pre code { background: none; padding: 0; color: #e0e0e0; font-size: 0.76em; line-height: 1.5; }
  table { width: 100%; border-collapse: collapse; font-size: 0.82em; }
  th { background: #1a1a1a; color: #00FFA3; padding: 7px 12px; text-align: left; }
  td { padding: 7px 12px; border-bottom: 1px solid #1e1e1e; }
  strong { color: #00FFA3; }
  blockquote { border-left: 3px solid #333; padding-left: 1em; color: #888; font-size: 0.82em; margin: 0.6em 0 0; }
---

<!-- 1. 표지 -->

# LLM 출력 불확실성 통제
## 확률적 AI를 서비스 품질로 바꾼 방식

<br>

VOD 채팅 감정 분석에 LLM을 쓴다.  
그런데 LLM은 **같은 입력에도 매번 다른 형식과 값을 돌려줄 수 있다.**  
예외 없이 조용히 틀린다.

<br>

이 슬라이드는 그 문제를 어떻게 발견했고,  
**왜 3계층 방어가 필요했는지**를 다룬다.

---

<!-- 2. 감정 분류 체계 -->

## 감정 분류 체계 — 7개 레이블

| 레이블 | 대표 채팅 패턴 | 의미 |
|---|---|---|
| `JOY` | ㅋㅋㅋ, 미쳤다, ㄷㄷ | 웃음·환호 |
| `HOPE` | 화이팅, 오 되겠는데 | 기대·응원 |
| `WONDER` | 헐, 와 진짜, 실화냐 | 놀람·감탄 |
| `HYPE` | ㄷㄷ 흥분, 대박 | 흥분·열기 |
| `SADNESS` | ㅠㅠ, 아깝다, 졌다 | 슬픔·아쉬움 |
| `ANGER` | 왜 이래, 말이 돼? | 분노·불만 |
| `DISGUST` | 노잼, 별로, 그냥 꺼 | 거부감 |

<br>

> `NEUTRAL`은 이 표에 없다. 두 가지 의미로 쓰인다.  
> ① 감정을 분류하기 **어려운 채팅**의 결과 레이블  
> ② 검증 실패·LLM 장애 시 시스템이 강제 할당하는 **안전 기본값**  
> 즉, "감정 없음"이 아니라 **"판단 불가 또는 복구 상태"** 를 의미한다.

---

<!-- 3. Hook — 같은 입력, 다른 출력 -->

## 같은 입력, 4가지 다른 응답

```
  입력: 동일한 채팅 묶음 30개          예외?    문제
  ──────────────────────────────────────────────────────────────────
  1회   {"joy":0.7, "hype":0.3}        없음  ✓  합계 1.0, 정상
  2회   [MD 래핑] {"joy":0.6,"hype":0.5} 없음  ✗  JSON 파싱 전 코드펜스 포함
  3회   {"joy":0.8, "hype":0.5, "wonder":0.2}  없음  ✗  합계 1.5
  4회   {"joy":0.7, "mood":0.3}         없음  ✗  알 수 없는 키
```

<br>

> `ObjectMapper`는 JSON을 읽을 수 있으면 예외를 발생시키지 않는다.  
> 2~4회 응답은 **오류처럼 보이지 않으면서 잘못된 데이터를 만든다.**

---

<!-- 3. Stakes — 오염 데이터가 흘러가는 경로 -->

## 오염 데이터 전파 경로

```
  OllamaAnalyzerService
       │
       │  {"joy":0.8, "hype":0.5, "wonder":0.2}   합계 1.5 — 예외 없음
       ▼
  parseResponse()                                  오염된 감정 레이블 생성
       │
       ▼                                           ↓ 각 채팅에 잘못된 감정 태그
  VodHighlightAnalyzer
  WindowStats 집계                                 hypeCount / laughCount 왜곡
       │
       ▼
  intensityScore / editabilityScore 계산           정상 구간보다 높게 산출
       │
       ▼
  totalScore → Kafka → vod_highlights 저장         잘못된 순위로 영구 저장
```

---

<!-- 4. 패턴 분석 — 오류는 예측 가능하다 -->

## LLM 오류 패턴 — 3가지 유형

| 패턴 | 원인 | 흡수 방법 |
|---|---|---|
| **마크다운 래핑** | LLM이 ` ```json ` 블럭으로 감쌈 | 파싱 전 코드 펜스 제거 |
| **합계 이탈** | 확률 모델 특성상 score 합이 1.0을 넘음 | clamp 후 합계로 재정규화 |
| **키 불일치** | 7개 키 대신 임의 키 반환 | 강제로 7개 키 완성, 합계≈0이면 NEUTRAL |

<br>

> **패턴이 예측 가능하면 코드로 흡수할 수 있다.**  
> 오류를 막는 게 아니라, 발생한 오류를 정해진 방식으로 처리한다.

---

<!-- 5. 3계층 방어 아키텍처 -->

## 3계층 방어 아키텍처

```
  채팅 입력
       │
  ┌────▼─────────────────────────────────────────────────────┐
  │  Layer 1 · 프롬프트 제약                                  │
  │  "반드시 JSON만 반환 / 7개 키 고정 / 합계 1.0 / 금지 단어" │
  └────┬─────────────────────────────────────────────────────┘
       │  LLM 응답 String
  ┌────▼─────────────────────────────────────────────────────┐
  │  Layer 2 · extractJsonText()                             │
  │  마크다운 코드 펜스 제거  →  순수 JSON 문자열             │
  └────┬─────────────────────────────────────────────────────┘
       │  Map<String, Double>
  ┌────▼─────────────────────────────────────────────────────┐
  │  Layer 3 · validateScores()                              │
  │  7개 키 완성  →  [0,1] clamp  →  합계≈0이면 NEUTRAL      │
  └────┬─────────────────────────────────────────────────────┘
       │  검증된 Map (합계 = 1.0 보장)
  VodHighlightAnalyzer · 감정 점수 사용
```

---

<!-- 6. OllamaAnalyzerService 클래스 구조 -->

## OllamaAnalyzerService — 방어 로직의 위치

```
  ┌────────────────────────────────────────────────────────────┐
  │                  OllamaAnalyzerService                     │
  ├────────────────────────────────────────────────────────────┤
  │  llmSlot : Semaphore(1)                                    │
  ├────────────────────────────────────────────────────────────┤
  │  + analyzeBatch(chats) : Mono<Map>                         │
  │    @CircuitBreaker("geminiApi")                            │
  │    fallbackAnalyzeBatch()  →  전체 NEUTRAL 반환            │
  │                                                            │
  │  + analyzeHighlight(payload) : Mono<HighlightDecision>     │
  │    RAG few-shot 연동  →  doAnalyzeHighlight()              │
  │                                                            │
  │  ~ extractJsonText(raw : String) : String      ← Layer 2  │
  │  ~ validateScores(map : Map) : Map             ← Layer 3  │
  │                                                            │
  │  - computeTimeout(n : int) : Duration                      │
  │    min(90, 20 + n × 1.5) 초                               │
  └────────────────────────────────────────────────────────────┘
```

> `extractJsonText` · `validateScores`는 package-private.  
> 방어 로직을 외부에서 우회할 수 없다.

---

<!-- 7. validateScores() 흐름 -->

## validateScores() — 출력 보장 계약

```
  Map<String, Double> 수신
         │
         ▼
  7개 키 완성      joy/hope/neutral/sadness/anger/wonder/disgust
  (누락 시 0.0)
         │
         ▼
  각 값 clamp      v < 0.0 → 0.0   /   v > 1.0 → 1.0
         │
         ▼
  합계 < 0.001 ?
    YES ──▶  모든 키 0.0,  neutral = 1.0  (NEUTRAL 강제)
    NO  ──▶  각 값 ÷ 합계  (재정규화 → 합계 = 1.0)
         │
         ▼
  반환: 합계 = 1.0,  모든 값 ∈ [0, 1]  항상 보장
```

---

<!-- 8. Fast / Slow Path — LLM 투입 최소화 -->

## Fast / Slow Path — 비용과 품질의 균형

```
  ┌────────────────────────────────────────────────────────┐
  │               ChatAnalysisProcessor                    │
  │                                                        │
  │  수신 채팅                                              │
  │      │                                                 │
  │      ├── DONATION / SUBSCRIPTION ──▶  분석 없이 통과   │
  │      │                                                 │
  │      └── CHAT ──▶  isAmbiguous()?  (top2 차 < 1)      │
  │                         │                              │
  │                  NO ────┴──── YES                      │
  │                   │               │                    │
  │                   ▼               ▼                    │
  │         HeuristicSentimentAnalyzer  ChatOptimizer      │
  │         키워드 매칭, 즉시 발행        필터·압축          │
  │                                      │                 │
  │                                      ▼                 │
  │                               OllamaAnalyzerService    │
  │                               Semaphore(1) + CB        │
  └────────────────────────────────────────────────────────┘
```

> LLM은 사람이 봐도 애매한 채팅에만 투입한다.  
> 명확한 채팅에는 Heuristic이 즉시 처리 — LLM 부하를 줄이면서 품질도 유지.

---

<!-- 9. Semaphore + Circuit Breaker -->

## Semaphore + Circuit Breaker — 동시성과 장애 격리

```
  요청들 ──▶  Semaphore(1)
              │
              │  동시에 하나만 통과, 나머지 대기/skip
              │  → LLM 과부하 방지
              ▼
              @CircuitBreaker("geminiApi")

              CLOSED  ─────────────▶  LLM API 호출
                                           │ 연속 실패
              OPEN    ◀────────────────────┘
                │
                ▼
              fallbackAnalyzeBatch()
              모든 채팅 → NEUTRAL 반환
              서비스 계속 동작
```

> 3계층 방어가 **출력 품질**을 지킨다면,  
> Semaphore + CB는 **서비스 가용성**을 지킨다.
