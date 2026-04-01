# Emotion Analysis Experiment Plan

## Goal

이 실험의 목표는 "감정 분석 정확도" 자체가 아니다.
가장 중요한 목표는 스트리머가 긴 방송 다시보기에서 유튜브 편집 하이라이트 후보를 빠르게 찾도록 돕는 것이다.

즉 결과물은 아래 질문에 답해야 한다.

- 어느 구간이 실제로 반응이 좋았는가
- 어느 지점이 편집 전환점으로 쓰기 좋은가
- 왜 이 구간이 후보인지 설명할 수 있는가
- 조용한 방송에서도 상대적으로 튀는 구간을 뽑아줄 수 있는가

## Product Principle

우리가 만들 것은 정교한 "감정 분류기" 하나가 아니라 아래를 제공하는 편집 보조 시스템이다.

- 편집 후보 구간 탐색
- 방송 흐름 변화 탐지
- 반응 유형 라벨링
- 후보 선정 이유 설명

## Output Definition

최종 출력은 다음 구조를 목표로 한다.

- 추천 구간 시작/종료 시각
- 하이라이트 강도
- 반응 유형
- 편집점 전환 강도
- 대표 채팅 1~3개
- 추천 이유 설명

예시:

- `01:25:30 ~ 01:26:00`
- `하이라이트 강도 18.4`
- `반응 유형: 웃음 + 놀람`
- `전환 강도: 높음`
- `대표 채팅: "와 이걸 산다고?"`
- `이전 30초 대비 채팅량 2.8배 증가, 참여자 급증, 감탄 표현 집중`

## Scoring Axes

### 1. Highlight Intensity Score

이 구간이 얼마나 강하게 반응을 끌어냈는지 측정한다.

구성 요소:

- 채팅 수
- 참여자 수
- 채팅 밀도
- 반복 메시지
- 특수문자 burst
- 짧은 감탄 burst

용도:

- 방송에서 가장 "시끄럽고 반응이 몰린 순간"을 찾는다

### 2. Transition Score

흐름이 얼마나 급격히 변했는지 측정한다.

구성 요소:

- 이전 구간 대비 채팅 수 변화율
- 이전 구간 대비 참여자 수 변화율
- 조용한 상태에서 급상승 여부
- 다음 구간까지 열기가 이어지는지
- 반응 유형 분포 변화

용도:

- 편집 시작점/컷 포인트를 잡는다

### 3. Editability Score

편집자가 실제로 잘라 쓰기 좋은 구간인지 측정한다.

구성 요소:

- intensity score
- transition score
- 대표 채팅 존재 여부
- 구간 내 메시지 집중도
- 전후 맥락 대비 독립성

용도:

- "재미있긴 한데 편집 포인트로는 애매한 구간"을 걸러낸다

### 4. Reaction Label

감정 분류는 절대 점수보다 편집 라벨로 사용한다.

초기 후보:

- 웃음
- 놀람
- 고조
- 긴장
- 혼란
- 감동
- 응원

용도:

- 하이라이트 카드의 톤 설명
- 편집자가 원하는 스타일로 빠르게 훑기

## Selection Strategy

하이라이트 후보 선정은 반드시 2단계로 나눈다.

### Stage 1. Candidate Generation

전체 30초 윈도우를 대상으로 점수를 계산한다.

보관 필드:

- window start/end
- message count
- participant count
- intensity score
- transition score
- editability score
- reaction label
- representative messages

### Stage 2. Final Selection

최종 카드로 보여줄 구간을 고른다.

원칙:

- 전역 상위 점수만 뽑지 않는다
- 시간대 분산을 반드시 보장한다
- 조용한 방송도 상대적 상위 구간을 최소 보장한다
- 편집 전환점 성격의 구간을 일부 별도 quota로 확보한다

초기 정책:

- 전역 상위 후보 50%
- 시간대 버킷 대표 30%
- 전환점 대표 20%

## Data Model Proposal

현재 하이라이트 DTO에 아래 필드를 확장하는 방향을 권장한다.

### VOD timeline point

- `messageCount`
- `participantCount`
- `activityScore`
- `transitionScore`
- `reactionLabel`
- `topMessage`

### VOD highlight point

- `highlightScore`
- `transitionScore`
- `editabilityScore`
- `reactionLabel`
- `reasonSummary`
- `topMessage`
- `secondaryMessage`
- `supportingSignals`

## UX Direction

프론트는 "감정 수치"가 아니라 "편집 후보 탐색기"처럼 보여야 한다.

핵심 UI:

- 전체 방송 흐름 차트
- 하이라이트 레일
- 편집 후보 카드
- 후보 이유 설명
- 반응 유형 필터

카드에서 보여줄 최소 정보:

- 타임스탬프
- 반응 유형
- 하이라이트 강도
- 전환 강도
- 대표 채팅
- 추천 이유

## Phase 1 Experiment Scope

1차 실험은 아래 범위로 제한한다.

- intensity score 재정의
- transition score 본격 도입
- reaction label을 편집 라벨 관점으로 정리
- 최종 선택을 전역/분산/전환 혼합 방식으로 변경
- 카드 설명 문구 개선

이번 단계에서는 하지 않는 것:

- 멀티모달 영상 장면 분석
- 음성 분석
- 정교한 LLM 문맥 요약
- 클립 자동 생성

## Success Criteria

성공 기준은 아래다.

- 6시간 이상 방송에서도 앞쪽에만 몰리지 않고 시간대 전반에서 후보가 나온다
- 조용한 방송에서도 최소한의 편집 후보가 나온다
- 대표 채팅이 대부분의 카드에 채워진다
- 사용자가 "왜 이 구간이 떴는지" 바로 이해할 수 있다
- 스트리머가 다시보기를 처음부터 훑는 시간을 줄였다고 느낀다

## Evaluation Checklist

테스트할 때 아래를 확인한다.

- 하이라이트가 특정 시간대에만 몰리는가
- 중간에 끊겼다가 갑자기 반응이 붙는 구간이 잡히는가
- 조용한 방송에서 상대적 상위 구간이 남는가
- 대표 채팅이 실제로 그 구간 분위기를 설명하는가
- 반응 유형 라벨이 편집자 관점에서 납득되는가
- 추천 이유 문구가 숫자 나열이 아니라 해석 가능한가

## Next Implementation Order

1. 점수 체계를 `intensity / transition / editability`로 분리
2. highlight DTO에 설명용 필드 추가
3. selection quota를 전역/분산/전환으로 분리
4. 반응 유형 라벨 재정의
5. 카드 설명 문구 개선
6. 테스트 VOD 세트로 결과 비교
