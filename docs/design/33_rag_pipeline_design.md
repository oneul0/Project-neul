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
---

<!-- 1. 표지 -->

# 채팅 → RAG → LLM 파이프라인
## 구조와 설계 의도

<br>

채팅 텍스트에서 의미 있는 벡터를 만들고, LLM 판단에 유사 사례를 주입하는 흐름.  
구현보다 **왜 이렇게 나눴고, 무엇을 숨겼고, 실패를 어떻게 격리했는가**를 다룬다.

<br>

`SRP` · `캡슐화` · `실패 격리` · `설정 외부화` · `DRY`

---

<!-- 2. 구조도 — 컴포넌트와 경계 -->

## 구조도 — 컴포넌트와 경계

```
┌─────────────── analyzer 서비스 ──────────────┐
│                                               │
│  OllamaAnalyzerService                        │
│    analyzeHighlight()                         │
│    fetchFewShotExamples()  ─────────────────────────────┐
│                                               │         │ POST /internal/rag/few-shot
└───────────────────────────────────────────────┘         │
                                                          ▼
┌─────────────── core-api 서비스 ──────────────────────────────────────┐
│                                                                       │
│  VodHighlightConsumer          ┌──────── rag 패키지 ───────────────┐  │
│  (Kafka → 저장 트리거)          │                                   │  │
│         │                      │  RagController                    │  │
│         │  flatMap              │  (내부 API 노출)        ◀─────────┼──┘
│         ▼                      │         │                         │  │
│  HighlightEmbeddingService ◀───┤         ▼                         │  │
│  (임베딩 생성 + 저장)            │  HighlightRetrievalService        │  │
│                                │  (3전략 검색 + 병합)               │  │
│                                │         │                         │  │
│                                │  PgVectorUtils                    │  │
│                                │  (float[] 직렬화)                  │  │
│                                └───────────────────────────────────┘  │
│                                                                       │
│  PostgreSQL (pgvector)                                                │
└───────────────────────────────────────────────────────────────────────┘
```

---

<!-- 3. 흐름도 1 — 채팅 → 벡터 저장 -->

## 흐름도 1 — 채팅 → 벡터 저장

```
  채팅 텍스트 (WebSocket 수신)
        │
        ▼  감정 태깅
  개별 채팅  →  JOY / HYPE / ANGER / NEUTRAL / ...
        │
        ▼  30초 윈도우 집계 + 비율 정규화
  density_ratio · hype_ratio · laugh_ratio · unique_user_ratio
        │
        ▼  LLM 하이라이트 판정                          [analyzer]
  scene_label · reason_summary · keyword_summary
        │
        │  Kafka  vod-analyzed-topic
        ▼                                               [core-api]
  VodHighlightConsumer
    ├── R2DBC  →  vod_highlights 저장
    └── flatMap  →  embedAndStore()
                      ├── buildEmbeddingText()
                      ├── POST Ollama  →  float[768]
                      └── UPDATE embedding::vector  →  pgvector
```

> 저장과 임베딩은 같은 트랜잭션이 아니다.  
> 임베딩 실패 시 `onErrorResume` 으로 저장 결과만 반환 — **저장은 보장, 임베딩은 best-effort**.

---

<!-- 4. 흐름도 2 — LLM 판정 → RAG → 프롬프트 주입 -->

## 흐름도 2 — LLM 판정 → RAG → 프롬프트 주입

```
  analyzeHighlight(payload)                              [analyzer]
        │
        ├── fetchFewShotExamples()
        │     POST /internal/rag/few-shot?k=3
        │     timeout 5s  |  onErrorReturn ""
        │                         │                      [core-api]
        │                   retrieve(candidate, k=3)
        │                     ├── buildEmbeddingText()   ← 저장 시와 동일 메서드 (DRY)
        │                     ├── Ollama  →  float[768]
        │                     └── Mono.zip               ← A / B / C 병렬
        │                           └── merge()
        │                                 └── formatFewShot()
        │
        └── doAnalyzeHighlight(payload, fewShot)
              ollama-highlight-user.txt
              {{fewShotExamples}}  ←  주입
              Ollama LLM  →  { is_highlight, scene_label, scores }
```

> RAG는 LLM의 **보조**다. 실패해도 빈 문자열로 계속 진행.  
> LLM 판단 자체를 막지 않는다.

---

<!-- 5. SRP — 하나의 클래스, 하나의 이유 -->

## SRP — 하나의 클래스, 하나의 이유

| 컴포넌트 | 단일 책임 | 변경이 필요한 이유 |
|---|---|---|
| `HighlightEmbeddingService` | 임베딩 생성 + 저장 | 임베딩 모델 변경 시 |
| `HighlightRetrievalService` | 유사 사례 검색 | 검색 전략 변경 시 |
| `RagController` | 내부 RAG API 노출 | API 계약 변경 시 |
| `VodHighlightConsumer` | Kafka 소비 → 저장 트리거 | 이벤트 소스 변경 시 |
| `PgVectorUtils` | `float[]` → pgvector 직렬화 | pgvector 포맷 변경 시 |

<br>

```
  HighlightEmbeddingService  ←→  HighlightRetrievalService
  (저장 담당)                      (검색 담당)
        ↑                               ↑
  VodHighlightConsumer           RagController
  (트리거 담당)                    (API 담당)
```

> 역할이 달라 변경 이유가 다르다. 하나를 바꿔도 다른 쪽에 영향이 없다.

---

<!-- 6. 캡슐화 — 외부 노출 최소화 -->

## 캡슐화 — 외부 노출 최소화

```
  HighlightEmbeddingService
  ├── public   embedAndStore(VodHighlight)      ← Consumer가 호출
  ├── public   requestEmbeddingPublic(String)   ← RetrievalService가 재사용
  ├── package  buildEmbeddingText(VodHighlight) ← rag 패키지 안에서만
  └── private  requestEmbedding(String)         ← 내부 구현, 외부 불필요

  HighlightRetrievalService
  ├── public   retrieve(VodHighlight, int)      ← Controller가 호출
  ├── public   findMostSimilarLive(...)         ← V2 파이프라인이 호출
  ├── private  queryStrategyA/B/C(...)          ← 전략 구현 은닉
  └── private  merge(...)                       ← 병합 로직 은닉
```

<br>

> `buildEmbeddingText()`를 package-private으로 둔 이유:  
> 저장 시(`embedAndStore`)와 검색 시(`retrieve`) 모두 **같은 텍스트 형식**을 써야  
> 저장된 벡터와 쿼리 벡터가 같은 공간에 있다. 외부에서 다른 형식으로 호출할 여지를 없앤다.

---

<!-- 7. OCP + 실패 격리 -->

## OCP — 코드 수정 없이 전략 비율 변경

```java
@Value("${gak.rag.ratio-a:0.6}")   // 기본 60%
private double ratioA;

@Value("${gak.rag.ratio-b:0.2}")   // 기본 20%
private double ratioB;
// kC = totalK - kA - kB            나머지 자동 계산
```

A/B 테스트, 장르별 튜닝 — **코드 배포 없이 설정 파일만 수정**.  
전략 수식(`Mono.zip` + `merge`)은 그대로, 비율만 달라진다.

<br>

## 실패 격리 — 장애 전파 차단

| 지점 | 처리 | 의미 |
|---|---|---|
| `embedAndStore()` 실패 | `onErrorResume → Mono.just(highlight)` | 임베딩 없어도 저장 결과 반환 |
| `fetchFewShotExamples()` 실패 | `onErrorReturn("")` | RAG 없어도 LLM 계속 |
| `retrieve()` 실패 | `onErrorResume → Mono.just(List.of())` | 빈 목록으로 응답 |

---

<!-- 8. 설계 의도 요약 -->

## 설계 의도 요약

```
  채팅 텍스트
      │
      ▼  [감정 태깅]  Fast / Slow 분기 — 비용 최소화
      │
      ▼  [비율 정규화]  절대값 대신 비율 — 채널 규모 무관 비교
      │
      ▼  [임베딩]  buildEmbeddingText() 하나로 저장·검색 통일 (DRY)
      │
      ▼  [벡터 저장]  best-effort — 실패해도 저장은 보장
      │
      ▼  [RAG 검색]  3전략 병렬 — 성능 + 다양성
      │
      ▼  [few-shot 주입]  RAG 실패 = LLM 중단이 아님 (실패 격리)
      │
      ▼  [LLM 판정]  유사 사례가 있으면 더 일관된 레이블
```

<br>

| 원칙 | 적용 지점 |
|---|---|
| **SRP** | 임베딩·검색·소비·API를 별도 클래스로 분리 |
| **캡슐화** | package-private 텍스트 빌더, private 전략 메서드 |
| **OCP** | `@Value` ratio — 전략 비율을 코드 밖으로 |
| **실패 격리** | `onErrorReturn / onErrorResume` 각 경계마다 |
| **DRY** | `buildEmbeddingText()` 저장·검색 공유 |
