# 2026-04-01 작업 정리

## 🎯 목표 (Goal)

한 문장으로 요약: VOD 하이라이트 기능을 "감정 점수 출력"이 아니라 "스트리머가 편집 포인트를 빠르게 찾는 도구"로 다시 정의하고, 그에 맞는 점수 구조와 UI를 실험하는 것.

이 목표는 단순히 알고리즘을 바꾸는 일이 아니었다.
기존 하이라이트 결과를 보면서 계속 남았던 질문이 있었다.

- 점수는 있는데 무슨 의미인지 바로 이해되지 않는다.
- 감정 라벨이 붙어도 편집자가 바로 쓸 수 있는 정보처럼 느껴지진 않는다.
- 왜 특정 구간이 추천됐는지 설명이 약하다.
- 사용자 화면에 내부 점수 축을 그대로 보여주면 오히려 더 복잡하다.

결국 이날의 목표는 아래처럼 다시 정리되었다.

- 감정 분석 정확도보다 편집 후보 탐색을 우선한다.
- 점수는 내부적으로 세분화하되, 사용자에게는 단순하게 보인다.
- 결과는 "이 구간이 왜 추천됐는지" 설명할 수 있어야 한다.

---

## 1. 🏗️ 아키텍처 및 설계 (Architecture & Design)

### 어떤 구조로 만들었는가

이날은 특히 VOD 하이라이트 결과 구조를 다시 설계했다.

기존 구조는 대체로 아래처럼 단순했다.

- `highlightScore`
- `category`
- `description`
- `topMessage`

하지만 편집 후보 탐색 용도로는 이 정도만으로 부족했다.
그래서 내부 분석 축과 사용자 노출 축을 분리하는 방향으로 구조를 다시 잡았다.

### 데이터 흐름

`Collector -> Kafka(vod-raw-chat-topic) -> Analyzer -> Kafka(vod-analyzed-topic) -> Core API -> Frontend`

이 기본 흐름은 유지하되,
analyzer가 최종 하이라이트를 만들 때 더 풍부한 필드를 계산해서 같이 넘기도록 확장했다.

### 새로 정리한 점수 축

#### 1. Highlight Score

최종 추천 강도.

- 사용자에게는 이 값만 `추천 강도`처럼 보이게 한다.
- 내부적으로는 여러 축을 조합한 결과다.

#### 2. Intensity Score

이 구간에 반응이 얼마나 몰렸는지 보는 점수.

예를 들어:

- 채팅량
- 참여자 수
- burst
- 반복 메시지

같은 요소를 반영한다.

#### 3. Transition Score

직전 구간 대비 흐름 변화가 얼마나 큰지 보는 점수.

예를 들어:

- 조용하다가 갑자기 채팅이 몰렸는지
- 분위기가 전환됐는지
- 앞 구간과 비교했을 때 편집점으로 보일 만한 차이가 있는지

를 반영한다.

#### 4. Editability Score

이 구간이 짧게 잘라서 하이라이트로 쓰기 좋은가를 보는 점수.

즉 단순히 반응이 많다고 좋은 게 아니라,
"편집 포인트로 쓰기 좋은 구간인가"를 별도로 보려고 한 것이다.

### 핵심 설계 결정

#### 1. 내부 점수와 사용자 노출을 분리

이게 이날 가장 중요한 설계 결정이었다.

내부적으로는 `intensity / transition / editability`로 세분화하고,
사용자에게는 아래처럼 단순하게 보이게 한다.

- 추천 강도
- 추천 이유
- 대표 채팅

#### 2. 점수보다 설명이 더 중요하다고 판단

스트리머가 정말 원하는 건
"이 점수는 7.2고 transition이 4.3이다"가 아니라

"왜 이 구간을 먼저 보라고 하는지"

를 이해하는 것이다.

그래서 `reasonSummary` 필드를 별도로 두고,
추천 이유를 자연어 문장으로 보여주는 쪽으로 정리했다.

#### 3. 편집 후보는 감정 분류기보다 탐색 도구여야 함

이 기능의 목표를 다시 정의하면서,
하이라이트 기능을 감정 분석기처럼 보이게 만드는 대신
"편집 후보 탐색기"처럼 보이게 만드는 방향으로 기획을 바꿨다.

---

## 2. 💥 트러블슈팅 (Troubleshooting)

### 문제 1: 하이라이트 결과가 "점수는 있는데 왜 뽑혔는지" 설명이 약했던 문제

**Situation**

하이라이트 결과는 생성되지만, 결과를 보면
"그래서 왜 이 구간이 추천됐지?"라는 질문이 남았다.

**Task**

결과를 단순 숫자 출력이 아니라, 편집 후보 추천으로 해석 가능하게 만들어야 했다.

**Action**

- 점수 하나로 묶여 있던 정보를 역할별 점수로 분리했다.
- `reasonSummary` 필드를 추가해서 추천 이유를 생성하도록 했다.
- 단순 라벨이 아니라, 사용자가 읽을 수 있는 문장형 설명을 붙이기 시작했다.

**Result**

- 하이라이트 결과가 "숫자 목록"에서 "추천 근거가 있는 후보"로 바뀌기 시작했다.
- 이후 UI에서도 이 이유를 중심으로 보여줄 수 있게 됐다.

복붙용 데이터 구조 예시:

```java
VodHighlightPoint point = VodHighlightPoint.builder()
        .videoNo(videoNo)
        .startSeconds(ranked.startSeconds())
        .endSeconds(ranked.endSeconds())
        .highlightScore(ranked.score())
        .intensityScore(ranked.intensityScore())
        .transitionScore(ranked.transitionScore())
        .editabilityScore(ranked.editabilityScore())
        .category(ranked.category())
        .reactionLabel(ranked.reactionLabel())
        .description(ranked.description())
        .reasonSummary(ranked.reasonSummary())
        .topMessage(ranked.topMessage())
        .build();
```

### 문제 2: 점수를 하나로만 보면 편집 포인트 성격을 설명하기 어려웠던 문제

**Situation**

어떤 구간은 반응이 많지만 편집 전환점은 약하고,
어떤 구간은 채팅량은 적지만 분위기 변화가 커서 편집점으로 좋았다.
그런데 점수 하나만 보면 이 차이를 설명하기 어려웠다.

**Task**

하이라이트를 구성하는 내부 신호를 분리해서,
이후 실험에서 어떤 요소가 실제 편집 포인트에 더 유효한지 비교할 수 있어야 했다.

**Action**

- 점수를 `intensity`, `transition`, `editability`로 분리했다.
- DTO, 엔티티, consumer, DB 스키마까지 같이 확장했다.
- Flyway `V3` 마이그레이션으로 필드를 반영했다.

**Result**

- 내부 실험이 훨씬 쉬워졌다.
- 나중에 "전환점은 좋은데 강도는 낮다" 같은 유형 비교가 가능해졌다.

복붙용 마이그레이션 예시:

```sql
ALTER TABLE vod_highlights
    ADD COLUMN IF NOT EXISTS intensity_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS transition_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS editability_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS reaction_label VARCHAR(100),
    ADD COLUMN IF NOT EXISTS reason_summary TEXT;
```

### 문제 3: 사용자에게 내부 점수 축을 그대로 보여주니 오히려 더 어려웠던 문제

**Situation**

한 번은 프론트에서 `Intensity`, `Transition`, `Editability`를 그대로 보여줬다.
실험자는 이해할 수 있어도, 실제 사용자 입장에서는 너무 기술적인 화면이 됐다.

**Task**

내부 구조는 유지하면서도, 사용자에게는 더 단순하고 해석 가능한 형태로 바꿔야 했다.

**Action**

- 세 점수 표를 화면에서 제거했다.
- 대신 `추천 강도`, `추천 이유`, `대표 채팅` 중심으로 UI를 정리했다.
- 필요한 경우에만 `반응 밀집도`, `흐름 전환` 같은 쉬운 표현으로 보조 정보를 노출했다.

**Result**

- 화면이 기술 문서처럼 보이지 않게 됐다.
- 사용자는 "점수 공부"보다 "어떤 구간을 먼저 볼지"에 집중할 수 있게 됐다.

복붙용 프론트 예시:

```tsx
<span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-amber-700">
  <Zap className="h-3.5 w-3.5" />
  추천 강도 {item.highlightScore.toFixed(1)}
</span>

<p className="mt-1 text-sm leading-6 text-slate-600">
  {item.reasonSummary || "추천 이유를 계산 중입니다."}
</p>
```

### 문제 4: 추천 이유 문구가 너무 기술적이어서 "사람 말"처럼 읽히지 않았던 문제

**Situation**

점수 구조를 나누고 필드를 추가한 뒤에도, 추천 이유가 숫자 나열이나 내부 용어처럼 느껴졌다.

**Task**

추천 이유를 스트리머가 바로 이해할 수 있는 문장으로 번역해야 했다.

**Action**

- 추천 이유를 자연어 문장으로 재구성했다.
- 예를 들어 아래와 같은 표현으로 바꿨다.
  - 감탄이나 반복 반응이 눈에 띄게 몰렸어요.
  - 직전 구간보다 분위기가 확 바뀌는 편집 포인트예요.
  - 짧게 잘라 하이라이트로 쓰기 좋은 흐름이에요.
  - 조용한 흐름 속에서도 상대적으로 반응이 살아난 구간이에요.

**Result**

- 추천 결과가 "알고리즘 출력"보다 "도움말"처럼 읽히기 시작했다.
- 사용자가 추천 이유를 곧바로 납득하기 쉬워졌다.

복붙용 코드:

```java
private String buildReasonSummary(
        WindowStats window,
        String reactionLabel,
        double intensityScore,
        double transitionScore,
        double editabilityScore
) {
    List<String> reasons = new ArrayList<>();

    reasons.add(String.format("이 구간에서 채팅 %d개, 참여자 %d명이 반응했어요.", window.messageCount(), window.uniqueUsers()));

    if (window.burstSignal() >= 3.0) {
        reasons.add("감탄이나 반복 반응이 눈에 띄게 몰렸어요.");
    }
    if (window.repeatedMessageCount() >= 2) {
        reasons.add("비슷한 메시지가 여러 번 반복돼서 장면 반응이 또렷했어요.");
    }
    if (transitionScore >= 4.5) {
        reasons.add("직전 구간보다 분위기가 확 바뀌는 편집 포인트예요.");
    }
    if (editabilityScore >= 8.0) {
        reasons.add("짧게 잘라 하이라이트로 쓰기 좋은 흐름이에요.");
    }

    return String.join(" | ", reasons);
}
```

---

## 3. 💻 핵심 구현 코드 (Key Implementation)

### 1. 하이라이트 데이터 구조 확장

```java
public class VodHighlightPoint {
    private String videoNo;
    private int startSeconds;
    private int endSeconds;
    private double highlightScore;
    private double intensityScore;
    private double transitionScore;
    private double editabilityScore;
    private String category;
    private String reactionLabel;
    private String description;
    private String reasonSummary;
    private String topMessage;
}
```

### 2. 엔티티 확장

```java
private Double highlightScore;
private Double intensityScore;
private Double transitionScore;
private Double editabilityScore;

private String category;
private String reactionLabel;
private String description;
private String reasonSummary;
private String topMessage;
```

### 3. 최종 점수 조합

```java
double totalScore = (intensityScore * 0.55)
        + (transitionScore * 0.20)
        + (editabilityScore * 0.25);
```

### 4. 프론트에서 사용자 친화적 표시

```tsx
<span className="rounded-full border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-indigo-700">
  {item.reactionLabel || categoryLabel[item.category] || "편집 후보"}
</span>

<span className="inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-black tracking-[0.18em] text-amber-700">
  <Zap className="h-3.5 w-3.5" />
  추천 강도 {item.highlightScore.toFixed(1)}
</span>

<p className="mt-1 text-sm leading-6 text-slate-600">
  {item.reasonSummary || "추천 이유를 계산 중입니다."}
</p>
```

---

## 4. 💡 회고 및 배운 점 (Insights)

- 감정 분석은 목표가 아니라 재료라는 걸 더 분명하게 느꼈다.
- 실제 문제는 "감정을 맞추는 것"보다 "편집 시간을 줄이는 것"이었다.
- 내부 지표는 세분화할수록 좋지만, 사용자 화면은 오히려 더 단순해야 한다.
- 추천 시스템은 숫자보다 설명이 더 중요할 때가 많다.
- 이번 작업을 통해 "기술적으로 정교한 것"과 "사용자에게 이해되는 것"은 별개라는 점을 다시 확인했다.

---

## 🚀 다음 단계 (Next Steps)

- 전환점 탐지 비중을 더 높여 편집 컷 포인트 품질 개선
- 시간대 분산 정책을 더 강하게 만들어 후반부 후보도 잘 살리기
- 조용한 방송에서도 상대적으로 괜찮은 구간을 더 잘 남기기
- 추천 이유 문구를 더 자연스럽고 짧게 다듬기

## 관련 커밋

- `8d0419e` `feat: prototype editorial highlight scoring`
