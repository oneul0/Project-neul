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

# LLM 가드레일 구현
## 코드 방어 + 프롬프트 제약

<br>

LLM은 같은 입력에도 형식이 바뀌거나, 점수 합이 1을 넘거나, 모르는 키를 만들어 반환한다.  
예외 없이, 조용히.

<br>

이 슬라이드는 채팅 배치가 감정 점수로 바뀌는 경로 위에  
**어떤 코드를 어느 지점에 배치했는지**, 그리고  
**프롬프트 파일에 어떤 규칙을 직접 명시했는지**를 다룬다.

---

<!-- 2. 전체 방어 흐름 -->

## 전체 방어 흐름

```
  채팅 배치 (raw)
       │
  ┌────▼──────────────────────────────────────────────┐
  │  입력 정제                                         │
  │  빈 채팅 제거 → 배치 30개 상한 → 문자 3,000자 상한  │
  └────┬──────────────────────────────────────────────┘
       │
  ┌────▼──────────────────────────────────────────────┐
  │  동시성·타임아웃 제어                               │
  │  Semaphore(1) — 슬롯 없으면 skip                  │
  │  동적 타임아웃 — min(90, 20 + 배치 수 × 1.5)초     │
  └────┬──────────────────────────────────────────────┘
       │
  ┌────▼──────────────────────────────────────────────┐
  │  프롬프트 제약 (템플릿 파일에 직접 명시)             │
  │  JSON만 반환 · 합계 1.0 · NEUTRAL 남발 억제 등      │
  └────┬──────────────────────────────────────────────┘
       │  LLM 호출
       │
       ├── 장애 ──▶  Circuit Breaker → 전체 NEUTRAL 반환
       │
  ┌────▼──────────────────────────────────────────────┐
  │  응답 정제                                         │
  │  마크다운 코드 펜스 제거 → JSON 경계 추출            │
  └────┬──────────────────────────────────────────────┘
       │
  ┌────▼──────────────────────────────────────────────┐
  │  출력 검증                                         │
  │  7개 키 완결 → [0,1] 클램핑 → 합계≈0 → NEUTRAL 강제 │
  └────┬──────────────────────────────────────────────┘
       │
  감정 점수 맵  (합계 = 1.0, 키 = 7개 보장)
```

---

<!-- 3. 코드 방어 — 입력 정제 -->

## 입력 정제

```java
// 1. 빈 채팅 제거
List<CompressedChat> filtered = chats.stream()
    .filter(c -> c.getContent() != null && !c.getContent().isBlank())
    .collect(Collectors.toList());

// 2. 배치 크기 상한 (30개)
List<CompressedChat> sized = filtered.size() > MAX_BATCH_SIZE
    ? filtered.subList(0, MAX_BATCH_SIZE)   // MAX_BATCH_SIZE = 30
    : filtered;

// 3. 총 문자 수 상한 (3,000자) — 토큰 비용 · 타임아웃 방지
List<CompressedChat> result = new ArrayList<>();
int totalChars = 0;
for (CompressedChat chat : sized) {
    int len = chat.getContent().length();
    if (totalChars + len > MAX_INPUT_CHARS) break;   // MAX_INPUT_CHARS = 3000
    result.add(chat);
    totalChars += len;
}
```

> 세 단계 모두 LLM 호출 전에 실행된다.  
> 크기 초과 시 카운터(`gak.llm.batch.capped`)로 관측 — 로그 없이도 트렌드 확인 가능.

---

<!-- 4. 코드 방어 — 동시성·타임아웃 -->

## 동시성·타임아웃 제어

```java
// Semaphore(1): 동시 LLM 호출 1개로 제한
// 슬롯이 없으면 대기 없이 즉시 skip — 과부하 방지
private final Semaphore llmSlot = new Semaphore(1);

if (!llmSlot.tryAcquire()) {
    recordCount("gak.llm.batch.skipped");
    return Mono.just(List.of());          // skip
}
return doAnalyzeBatch(capped)
    .doFinally(ignored -> llmSlot.release());
```

```java
// 동적 타임아웃: 배치 크기에 비례
// 고정 60초 대신 실제 입력 크기에 맞게 — 작은 배치는 빠르게, 큰 배치는 여유 있게
long seconds = Math.min(90L, 20L + (long)(batchSize * 1.5));
.timeout(Duration.ofSeconds(seconds))
```

```java
// Circuit Breaker: 연속 실패 시 자동 차단 → 전체 NEUTRAL 반환
@CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
public Mono<List<AnalyzedChatMessage>> analyzeBatch(...) { ... }

public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(..., Throwable t) {
    return Mono.just(createFallbackMessages(chats));  // 모든 채팅 → NEUTRAL
}
```

---

<!-- 5. 코드 방어 — 응답 정제 -->

## 응답 정제

LLM이 JSON을 마크다운 코드 펜스로 감싸 반환할 때를 흡수한다.

```java
// LLM 응답 String → 순수 JSON 문자열 추출
int firstBrace   = text.indexOf('{');
int firstBracket = text.indexOf('[');

// 가장 앞의 괄호부터 가장 뒤의 괄호까지 잘라냄
int start = (firstBrace != -1 && firstBracket != -1)
    ? Math.min(firstBrace, firstBracket)
    : (firstBrace != -1 ? firstBrace : firstBracket);

int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));

String json = text.substring(start, end + 1)
                  .replace("```json", "")   // 코드 펜스 제거
                  .replace("```", "")
                  .trim();
```

> 마크다운 제거 후에도 파싱 실패 시 `catch` 블록이 NEUTRAL 리스트를 반환한다.  
> 예외가 전파되지 않는다 — 배치 전체가 드롭되는 대신 NEUTRAL로 교체된다.

---

<!-- 6. 코드 방어 — 출력 검증 -->

## 출력 검증

어떤 LLM 응답이 와도 출력은 항상 같은 형식을 보장한다.

```java
private static final Set<String> VALID_EMOTIONS =
    Set.of("JOY", "HOPE", "NEUTRAL", "SADNESS", "ANGER", "WONDER", "DISGUST");

// 1. 7개 키 완결 보장 + [0, 1] 클램핑
for (String emotion : VALID_EMOTIONS) {
    double score = raw.getOrDefault(emotion, 0.0);      // 누락 키 → 0.0
    validated.put(emotion, Math.max(0.0, Math.min(1.0, score)));  // clamp
}

// 2. 합계 ≈ 0 → NEUTRAL 강제 (zero-score 뭉침 처리)
double total = validated.values().stream().mapToDouble(Double::doubleValue).sum();
if (total < 0.001) {
    recordCount("gak.llm.output.zeroed");
    return createNeutralScores();    // NEUTRAL = 1.0, 나머지 = 0.0
}

// 3. 재정규화는 하지 않음 — 합계 > 1.0이어도 클램핑 후 그대로 사용
//    (재정규화 시 도리어 비율 왜곡 가능성이 있어 제외)
```

> `raw`가 `null`이거나 비어 있어도 동일한 경로를 탄다 — 항상 7개 키 맵을 반환.

---

<!-- 7. 프롬프트 제약 — 감정 분석 -->

## 프롬프트 제약 — 감정 분석

`ollama-sentiment-system.txt` 에 직접 명시한 규칙들.

```
너는 한국어 스트리밍 채팅의 감정과 반응 키워드를 분석하는 전문 편집 보조야.

규칙:
1. 제공된 채팅에 실제로 등장한 표현만 사용해 3~5개 키워드를 추출할 것.
   없는 키워드는 만들지 말 것.                           ← 할루시네이션 방지
2. ㄹㅇ, ㅇㅈ 같은 반응어는 앞뒤 문맥을 보고 감정을 이어받아 해석할 것.
3. 가능하면 NEUTRAL 남발을 피하고,
   가장 지배적인 감정을 분명하게 분류할 것.              ← zero-score 뭉침 억제
4. 각 messageId의 scores 합계는 반드시 1.0이어야 한다.  ← 합계 규칙 선제 강제
5. 출력은 반드시 JSON만 반환할 것.                      ← 형식 강제
```

> 코드 가드가 사후 교정이라면, 프롬프트 규칙은 애초에 잘못된 형식을 만들지 않도록 하는 사전 억제다.  
> 둘 다 없으면 보완이 되지 않는다 — 프롬프트만 있으면 형식 위반을 처리 못하고, 코드만 있으면 zero-score 패턴 자체가 반복된다.

---

<!-- 8. 프롬프트 제약 — 하이라이트 판정 -->

## 프롬프트 제약 — 하이라이트 판정

`ollama-highlight-system.txt` + `ollama-highlight-user.txt`

```
[시스템 프롬프트]
너는 10만 구독자를 보유한 게임 하이라이트 채널의 전문 편집자야.  ← 페르소나

네거티브 필터: 방종 인사, 단순 도배, 친목 채팅은 하이라이트에서 제외할 것.
category는 슈퍼플레이 / 대참사 / 운 / 소통 중 하나만 쓸 것.    ← 허용 목록 강제
긍정 판정은 채팅 내용과 통계 지표를 함께 근거로 삼을 것.
출력은 반드시 JSON만 반환할 것.
```

```
[유저 프롬프트 — 수치 주입 구조]
- 밀도: 평소 대비 {{densityRatio}}배  /  Z-Score: {{zScore}}
- 반응 분포: hype={{hypeRatio}}  laugh={{laughRatio}}  tension={{tensionRatio}}
- 네거티브: 반복={{repeatedRatio}}  도배={{dominantSenderRatio}}  방종={{goodbyeRatio}}

{{fewShotExamples}}    ← RAG 검색 결과 주입
```

> 수치를 유저 프롬프트에 직접 포함시키는 이유:  
> LLM이 채팅 텍스트 인상이 아닌 **정량 지표 기반으로 판정**하도록 유도하기 위해.  
> 같은 채팅도 densityRatio 1.2와 5.0은 전혀 다른 하이라이트 후보다.

---

<!-- 9. 채팅 단위 감정 분류 -->

## 채팅 단위 감정 분류

```
  채팅 메시지 하나
       │
       ▼  키워드 매칭 → 감정별 카운트
       │
       ├── 상위 2개 카운트 차이 < 1  ──▶  모호 판정 → LLM 경로
       │                                    LLM이 확률 분포로 반환
       │                                    { JOY:0.7, HYPE:0.2, WONDER:0.1, ... }
       │                                    → 7개 점수 그대로 저장
       │
       └── 차이 ≥ 1  ──────────────▶  명확 판정 → 휴리스틱 경로
                                         최고 카운트 감정 = 1.0, 나머지 = 0.0
                                         { JOY:1.0, HYPE:0.0, WONDER:0.0, ... }
```

<br>

| 분류 방식 | 기준 | 점수 형태 |
|---|---|---|
| 휴리스틱 | 키워드 매칭 최다 감정 | 승자 1.0 / 나머지 0.0 (이진) |
| LLM | 확률 분포 추론 | 7개 값의 합계 ≈ 1.0 (연속) |
| 안전 기본값 | 합계 < 0.001 또는 LLM 장애 | NEUTRAL = 1.0 / 나머지 0.0 |

> 최종 레이블은 저장 시 별도로 추출하지 않는다.  
> 7개 점수 맵 자체가 결과물이며, 소비 측에서 argmax를 통해 대표 감정을 읽는다.

---

<!-- 10. 구간 단위 감정 우세 -->

## 구간 단위 감정 우세

30초 윈도우 안의 채팅 전체를 모아 구간 대표 감정을 결정한다.

```
  30초 윈도우 채팅 전체
       │
       ▼  채팅마다 반응 토큰 카운트
       │  ㅋㅋ·lol  →  laughCount
       │  레전드·goat  →  hypeCount
       │  헐·와·omg  →  surpriseCount
       │  억까·짜증  →  tensionCount
       │
       ▼  비율 정규화
       │  ratio = 각 카운트 ÷ (laugh + hype + surprise + tension)
       │
       ├── 최고 비율 ≥ 0.1 (10%)  →  해당 감정이 구간 대표 (LAUGH / HYPE / WONDER / TENSION)
       └── 최고 비율 < 0.1         →  neutral  (반응 신호가 너무 분산됨)
```

<br>

> 구간 대표 감정(`emotion_dominance`)은 구간 점수 계산과 RAG 임베딩 텍스트에 모두 사용된다.  
> 채팅 단위 레이블(7개 감정)과 달리, 구간 레벨에서는 4개 카테고리로 압축된다.
