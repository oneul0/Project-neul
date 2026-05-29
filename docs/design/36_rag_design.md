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

# RAG 기반 하이라이트 판정 보강
## LLM에게 과거 사례를 보여주는 방식과 그 이유

<br>

LLM은 하이라이트 후보를 볼 때마다 **처음 보는 것처럼 판단한다.**  
이 채널에서 3.5배 밀도가 많은 건지 적은 건지, 기준이 없다.

<br>

이 슬라이드는 그 한계를 어떻게 발견했고,  
**왜 단순 유사도 검색으로는 부족했는지,**  
그리고 3전략 혼합 검색에 어떻게 도달했는지를 다룬다.

---

<!-- 2. 주요 용어 정의 -->

## 주요 용어

| 용어 | 값 예시 | 의미 |
|---|---|---|
| `category` | FPS · RPG · 스포츠 · 버라이어티 | 영상 메타데이터 기반 장르 |
| `scene_label` | PEAK · 슈퍼플레이 · 대참사 · 운 · 소통 · 역전각 · 클러치 | LLM이 구간 내용을 보고 판정하는 하이라이트 유형 |
| `emotion_dominance` | HYPE · LAUGH · WONDER · TENSION | 30초 구간의 감정 비율 중 가장 높은 감정 |
| `density_ratio` | 3.5x | 이 구간의 채팅 수 ÷ 채널 평균 채팅 수 |
| `highlight_score` | 0.0 ~ 1.0 | 구간 전체 점수 (intensity · transition · editability 합산) |

<br>

> `scene_label`은 LLM이 판정한다 — 사전 정의 키워드 매칭이 아니다.  
> `density_ratio`는 절대 수가 아닌 **채널 평균 대비 배율** — 채널 규모와 무관하게 비교 가능.

---

<!-- 3. Hook — LLM은 판단 기준이 없다 -->

## LLM의 채널 기준 부재

```
  density = 3.5x   hype_ratio = 0.80   구간 길이 30초

  채널 A  (평소 채팅 20개/분 — 조용한 편)
          이 구간: 70개/분   → density 3.5x

  채널 B  (평소 채팅 100개/분 — 항상 시끄러움)
          이 구간: 350개/분  → density 3.5x   ← 하지만 평범한 수준
```

```
  LLM이 받는 것:  density=3.5x  hype=0.80  category=FPS  ...
  LLM이 모르는 것:  이 채널에서 3.5x가 많은 것인가 / 적은 것인가

  → 같은 수치라도 채널마다 의미가 다르지만,
    LLM은 매 호출이 독립적이라 기준을 학습하지 않는다.
```

---

<!-- 4. 단순 유사도 검색은 왜 부족한가 -->

## 단순 유사도 검색의 한계

```
  현재 후보:  density=3.5x  hype=0.80  category=FPS

  ── 유사 벡터 상위 3개 검색 ──────────────────────────────────
  사례1   FPS   density=3.4x   hype=0.79   is_highlight: false
  사례2   FPS   density=3.5x   hype=0.81   is_highlight: false
  사례3   FPS   density=3.6x   hype=0.78   is_highlight: false
  ─────────────────────────────────────────────────────────────

  LLM: "유사한 사례가 모두 하이라이트 아님 → 이것도 아님"
```

<br>

> 하이라이트는 전체 구간의 5~10%다.  
> 유사도가 높은 사례 대부분은 **평범한 구간**이다.  
> 비슷한 사례만 주면 LLM의 기준이 낮은 쪽으로 고착된다.

---

<!-- 5. 인사이트 — 대비가 필요하다 -->

## 3전략 설계 원칙 — 기준·대비·일반화

```
  좋은 예시 셋 = "이 장르의 기준" + "더 높은 기준" + "다른 맥락"

  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐
  │  전략 A · 60%      │  │  전략 B · 20%      │  │  전략 C · 20%      │
  │  같은 카테고리     │  │  다른 카테고리     │  │  다른 videoNo      │
  │  코사인 유사도 ↑   │  │  highlight_score ↑ │  │  코사인 유사도 ↑   │
  ├────────────────────┤  ├────────────────────┤  ├────────────────────┤
  │  "같은 장르에서    │  │  "다른 장르의      │  │  "다른 채널에서    │
  │   이 수치면        │  │   최고 사례는      │  │   비슷한 반응은    │
  │   이 정도야"       │  │   이래"            │  │   이랬어"          │
  │  기준 제공         │  │  대비 제공         │  │  일반화            │
  └────────────────────┘  └────────────────────┘  └────────────────────┘
```

> A만 있으면 기준이 낮아진다.  
> B가 있으면 LLM이 "더 높은 기준"을 인식한다.  
> C가 있으면 채널 편향을 희석한다.

---

<!-- 6. 3전략 병렬 검색 구조 -->

## 3전략 병렬 검색 — Mono.zip

```
  후보 하이라이트
       │  buildEmbeddingText()  →  Ollama  →  float[768] = q
       │
       │              Mono.zip (동시 실행)
       ├──────────────────┬──────────────────┐
       ▼                  ▼                  ▼
  queryStrategyA      queryStrategyB      queryStrategyC
  ──────────────      ──────────────      ──────────────
  WHERE category      WHERE category      WHERE video_no
    = :cat              != :cat             != :videoNo
  ORDER BY            ORDER BY            ORDER BY
  embedding <=> q     highlight_score     embedding <=> q
  LIMIT kA            LIMIT kB            LIMIT kC
       │                  │                  │
       └──────────────────┴──────────────────┘
                          │
                    merge(A, B, C)
                    A→B→C 순, id 중복 제거, totalK로 자름
```

---

<!-- 7. 임베딩 설계 — 채널 규모 무관 -->

## buildEmbeddingText() — 채널 규모와 무관한 벡터 공간

```
  ┌──────────────────────────────────────────────────────────────┐
  │                                                              │
  │  [PEAK]                  FPS                                 │
  │   ↑ sceneLabel            ↑ category                        │
  │                                                              │
  │  dominant=HYPE    density=3.5x    unique=0.42                │
  │     ↑                 ↑               ↑                      │
  │  감정 우세         채널 평균 대비      고유 유저 비율           │
  │                  (절대값 ✗, 배율 ✓)  (도배 여부)              │
  │                                                              │
  │  signal: hype=0.80  laugh=0.10  surprise=0.05  tension=0.05  │
  │          ↑ 감정별 비율 (절대 수가 아닌 비율)                  │
  │                                                              │
  │  keywords: 킬 연속 클러치                                    │
  └──────────────────────────────────────────────────────────────┘
```

> 절대 수치가 아닌 비율 → 10만 채널과 1천 채널이 **같은 벡터 공간에서 비교 가능**.  
> 저장(`embedAndStore`)과 검색(`retrieve`) 모두 이 메서드를 재사용 (DRY).

---

<!-- 8. 컴포넌트 관계도 -->

## 컴포넌트 관계도 — RAG 두 가지 경로

```
  [analyzer 서비스]           [core-api 서비스]           [kafka → core-api]
  ─────────────────           ─────────────────────────  ─────────────────────
  OllamaAnalyzerService       RagController              V2StreamService
  analyzeHighlight()          getFewShot()               handleSpikeDetection()
       │                           │                           │
       │  POST /rag/few-shot        │  uses                    │  buildLiveEmbeddingText()
       └──────────────────────────▶│                           │         ↓
                                   ▼                           │  requestEmbeddingPublic()
  VodHighlightConsumer   HighlightRetrievalService ◀──────────┘
  consumeAnalyzed()      retrieve()  findMostSimilarLive()          │
       │                 queryStrategyA/B/C()                       │ kNN k=1
       │ flatMap              │ uses                                 ▼
       ▼                     ▼                            PostgreSQL vod_highlights
  HighlightEmbeddingService ←─────────                   .embedding  vector(768)
  embedAndStore()                                                    │
  requestEmbeddingPublic()                                           │ 유사 사례 발견
  buildEmbeddingText()  ← VOD 저장/검색 공통                         ▼
  generateInsight()     ← gemma:2b 인사이트                V2SimilarHighlightAlert
       │                                                   .insight = "시청자들이 ..."
       ▼
  PostgreSQL  vod_highlights.embedding  vector(768)
```

---

<!-- 9. few-shot 주입 흐름 -->

## few-shot 주입 — 전체 흐름

```
  analyzeHighlight(payload)                               [analyzer]
         │
         ├──▶  fetchFewShotExamples()
         │       POST /internal/rag/few-shot?k=3
         │       timeout 5s
         │       실패 → onErrorReturn ""  ← RAG 없어도 LLM 계속 진행
         │
         │  ← 성공 시
         │     "[유사 사례]\n사례1: [PEAK] FPS ... \n사례2: ..."
         │
         └──▶  doAnalyzeHighlight(payload, fewShot)
                      │
                      ▼
               ollama-highlight-user.txt
               ┌──────────────────────────────────┐
               │  ...density={{densityRatio}}...  │
               │  {{fewShotExamples}}              │  ← 주입
               │  반드시 JSON만 반환               │
               └──────────────────────────────────┘
                      │
                      ▼
               Ollama LLM → { is_highlight, scene_label, scores }
```

---

<!-- 10. 두 번째 RAG 사용처: 라이브 유사도 감지 -->

## RAG 사용처 ② — 실시간 유사 하이라이트 감지

```
  [Kafka] v2-aggregate topic
         │  V2AggregateFrame { topicLabel, emaPositive, emaNegative, ... }
         ▼
  V2StreamService.handleSpikeDetection()
         │
         ├── 스파이크 감지?  emaPositive > 0.55  또는  emaNegative > 0.45
         │   ┌─────────────────────────────────────────────────────┐
         │   │  buildLiveEmbeddingText(frame)                      │
         │   │  → 실시간 채팅 상태를 VOD 임베딩 포맷으로 변환      │
         │   │  → requestEmbeddingPublic()  → Ollama nomic-embed   │
         │   │  → float[768]                                       │
         │   └─────────────────────────────────────────────────────┘
         │
         ├── HighlightRetrievalService.findMostSimilarLive(vector, threshold=0.72)
         │   → kNN k=1, cosine similarity, vod_highlights.embedding
         │   → 유사도 ≥ threshold 이면 VodHighlight 반환
         │
         ├── 유사 사례 발견 시
         │   → generateInsight(prompt)  → Ollama gemma:2b
         │   → "시청자들이 ~하고 있어요" 1문장
         │
         └── SSE emit → 브라우저 민심 탭 하이라이트 감지 피드
```

> VOD 분석으로 쌓인 임베딩 DB가 라이브 감지의 **기준점** 역할을 한다.  
> VOD 데이터가 없으면 유사도 검색 자체가 동작하지 않는다.

---

<!-- 11. 임베딩 포맷 정렬 — 같은 벡터 공간 -->

## 임베딩 포맷 정렬 — 같은 텍스트 구조 = 같은 벡터 공간

```
  buildEmbeddingText(VodHighlight)           buildLiveEmbeddingText(V2AggregateFrame)
  ──────────────────────────────             ──────────────────────────────────────────
  "[PEAK] FPS"                               "[감동 장면] live"
  "dominant=HYPE density=3.5x unique=0.42"  "dominant=positive density=1.8x unique=0.85"
  "signal: hype=0.80 laugh=0.10 ..."        "signal: hype=0.82 laugh=0.37 ..."
  "keywords: 킬 연속 클러치"                "keywords: 감동 울었다 역대급"
  "시청자들이 클러치라고 반응"               "시청자들이 역대급이라고 반응"
        │                                          │
        ▼                                          ▼
   float[768]                               float[768]
        └──────────── cosine similarity ──────────┘
```

```
  포맷이 다르면 →  벡터 공간이 달라져  →  유사도 0.1 이하  →  매칭 실패

  포맷을 맞추면 →  같은 축으로 정렬  →  유사도 0.7~0.9  →  매칭 성공
```

> 같은 필드 순서, 같은 키워드, 같은 레이블 포맷을 사용해야  
> 두 벡터가 **같은 의미 공간**에서 비교 가능해진다.

---

<!-- 12. LLM 인사이트 생성 — gemma:2b -->

## LLM 인사이트 생성 — gemma:2b

```
  유사 사례 발견 직후 (threshold 통과 시에만 실행)

  buildInsightPrompt(frame, alert)
  ┌──────────────────────────────────────────────────────────────┐
  │  다음 채팅 상황을 보고 지금 시청자들이 어떻게 반응하는지     │
  │  구어체 한 문장으로만 출력해.                                │
  │                                                              │
  │  입력:                                                       │
  │  주제=감동 장면  키워드=감동,울었다,역대급                   │
  │  채팅="진짜 울었다 ㅠㅠ"  패턴=positive_spike               │
  │                                                              │
  │  출력 예시:                                                  │
  │  - 시청자들이 폭소하고 있어요                               │
  │  - 시청자들이 감동받고 있어요                               │
  │  - 시청자들이 긴장하고 있어요                               │
  │                                                              │
  │  규칙: "시청자들이 ~하고 있어요" 형태, 15자 이내, 딱 한 줄. │
  └──────────────────────────────────────────────────────────────┘
         │
         ▼  Ollama /api/generate (gemma:2b, stream=false)
         │
  sanitizeInsight(raw)
  ├── "출력:" 이후 텍스트 추출
  ├── 첫 번째 줄만
  ├── 격식체 → 구어체 변환  ("있습니다" → "있어요")
  └── 30자 초과 시 잘라냄
         │
         ▼
  "시청자들이 감동받고 있어요"  →  V2SimilarHighlightAlert.insight
```

> `nomic-embed-text` = 벡터 검색용 (768차원).  
> `gemma:2b` = 자연어 해설 생성용.  
> 두 모델이 같은 Ollama 인스턴스에서 서로 다른 역할을 담당한다.
