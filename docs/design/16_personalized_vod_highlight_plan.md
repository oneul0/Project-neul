# 개인화 가능한 VOD 편집 후보 구현 계획

작성일: 2026-04-01

## 1. 목표

현재 VOD 하이라이트는 "같은 영상이면 모든 사용자에게 같은 후보"를 보여주는 구조입니다.
다음 단계 목표는 두 가지입니다.

1. 하이라이트 선정 로직을 더 다채롭게 만들어 방송 맥락을 더 잘 읽기
2. 같은 VOD라도 사용자 선호와 취향에 따라 다시 정렬하거나 강조할 수 있게 만들기

즉 앞으로는

- `원본 분석 결과`
- `개인화된 해석 결과`

를 분리해서 가져가야 합니다.

## 2. 현재 상태 정리

지금 저장되는 축:

- `videoNo`
- `startSeconds`
- `endSeconds`
- `highlightScore`
- `intensityScore`
- `transitionScore`
- `editabilityScore`
- `reactionLabel`
- `description`
- `reasonSummary`
- `topMessage`

현재 한계:

- 하이라이트는 공용 결과만 있음
- 사용자 취향 정보 저장 구조가 없음
- 마이페이지에서 "내가 본 영상 / 내가 저장한 후보 / 내가 선호하는 태그"를 다시 보는 구조가 없음
- 같은 VOD라도 사용자별 다른 랭킹을 만들 수 있는 레이어가 없음

## 3. 핵심 방향

### 3-1. 원본 분석과 개인화는 분리

VOD 전체 분석은 비용이 크기 때문에 영상 단위로 한 번만 계산합니다.

즉:

- `videoNo` 기준 공용 분석은 1회
- 사용자별 해석은 그 위에서 재정렬

구조:

- 공용 레이어
  - timeline
  - highlight candidates
  - moment signals
  - context summary
- 개인화 레이어
  - preferred tags
  - disliked tags
  - watch/save/click history
  - ranking weight
  - pinned highlights

### 3-2. 하이라이트는 "확정 카드"보다 "후보 집합"으로 저장

현재는 최종 선택된 하이라이트 위주로 저장하고 있습니다.
개인화를 하려면 더 넓은 후보군을 저장해두는 것이 좋습니다.

즉 앞으로는:

- `top 20~30` 정도 후보군 저장
- 사용자 취향에 따라 그 안에서 정렬
- 필요 시 "웃긴 장면 위주", "긴장감 위주", "짧게 편집하기 좋은 장면 위주"로 다시 보기

### 3-3. 마이페이지는 "내 활동 + 내 취향 + 내 분석 보관함"

마이페이지에서 바로 확인할 수 있어야 하는 것:

- 내가 최근 분석한 VOD 목록
- 영상별 분석 상태
- 저장한 하이라이트
- 내가 자주 선택하는 태그/반응 유형
- 내가 자주 여는 후보 시간대

## 4. 하이라이트 선정 로직 확장 계획

현재 로직은 주로

- intensity
- transition
- editability

축으로 보고 있습니다.

여기에 추가할 분석 기준:

### A. 장면 맥락 변화

- 이전 30초와 현재 30초의 반응 차이
- 현재 30초와 다음 30초의 반응 지속 여부
- 분위기 전환의 방향
  - 잔잔 -> 폭발
  - 웃음 -> 긴장
  - 긴장 -> 해소

### B. 반응 밀도 외 맥락 신호

- 참여자 증가율
- 새 참여자 유입 비율
- 같은 반응이 얼마나 빠르게 집중되는지
- 반응 지속 시간

### C. 편집 관점 신호

- 오프닝 컷으로 쓰기 좋은 구간
- punch line처럼 짧게 잘라 쓰기 좋은 구간
- 빌드업 뒤 터지는 구간
- 시청자 반응이 늦게 붙는 구간

### D. 장면 유형 태깅

내부 태그는 더 풍부하게 계산하되, 사용자에게는 단순한 표현으로 매핑합니다.

내부 후보:

- LAUGH
- SURPRISE
- HYPE
- TENSION
- RELEASE
- BUILDUP
- CHAOS
- HEARTWARMING

사용자 표현 예시:

- 웃음이 터진 장면
- 분위기가 확 바뀐 장면
- 반응이 몰린 장면
- 긴장감이 높은 장면
- 짧게 잘라 쓰기 좋은 장면

## 5. 개인화 데이터 모델 계획

### 5-1. 공용 분석 결과

기존 `vod_highlights`는 유지하되, 장기적으로는 후보군 저장을 고려합니다.

추가 후보 테이블 예시:

- `vod_highlight_candidates`
  - `id`
  - `video_no`
  - `start_seconds`
  - `end_seconds`
  - `base_highlight_score`
  - `intensity_score`
  - `transition_score`
  - `editability_score`
  - `reaction_label`
  - `context_label`
  - `description`
  - `reason_summary`
  - `top_message`
  - `signal_payload` JSON

### 5-2. 사용자 선호

새 테이블 예시:

- `user_vod_preferences`
  - `owner_id`
  - `preferred_reaction_labels` JSON
  - `preferred_context_labels` JSON
  - `preferred_clip_length` (SHORT / MID / LONG)
  - `preferred_energy_level`
  - `updated_at`

### 5-3. 사용자 활동 로그

- `user_vod_activity`
  - `id`
  - `owner_id`
  - `video_no`
  - `highlight_id`
  - `action_type` (OPEN / SAVE / PIN / DISMISS / CLIP_INTENT)
  - `created_at`

### 5-4. 사용자 분석 보관함

- `user_vod_library`
  - `owner_id`
  - `video_no`
  - `last_viewed_at`
  - `last_personalized_at`
  - `status`

## 6. API 계획

### 공용 분석

- `GET /api/v1/vod/{videoNo}/highlights`
- `GET /api/v1/vod/{videoNo}/timeline`
- `POST /api/v1/vod/{videoNo}/analyze`

### 개인화

- `GET /api/v1/me/vod-library`
  - 내가 최근 열어본/분석한 VOD 목록
- `GET /api/v1/me/vod/{videoNo}/highlights`
  - 내 취향 기준으로 재정렬된 후보
- `PUT /api/v1/me/preferences/vod`
  - 선호 태그/반응 업데이트
- `POST /api/v1/me/vod/{videoNo}/highlights/{highlightId}/action`
  - 저장, 핀, 관심 없음, 다시 보기 등 행동 기록

## 7. 추천 랭킹 구조

개인화 점수는 공용 점수 위에 얹습니다.

예시:

`personalizedScore = baseScore * 0.65 + preferenceMatch * 0.2 + activityAffinity * 0.15`

설명:

- `baseScore`
  - 전체 사용자 기준 좋은 후보인지
- `preferenceMatch`
  - 사용자가 선호하는 반응/맥락과 맞는지
- `activityAffinity`
  - 사용자가 과거에 자주 열어본 스타일과 비슷한지

중요:

- 공용 분석 결과를 덮어쓰지 않음
- 개인화 점수는 조회 시점 또는 캐시 시점에 계산

## 8. 프론트엔드 계획

### VOD 결과 화면

추가할 요소:

- `기본 추천 / 내 취향 추천` 토글
- 선호 태그 필터
- "이런 장면 더 보여줘" 버튼
- "이런 장면은 덜 보여줘" 버튼

### 마이페이지

초기 구성:

- 최근 분석한 VOD
- 저장한 하이라이트
- 자주 보는 반응 태그
- 내가 선호하는 장면 유형

## 9. 구현 순서

### Phase 1. 공용 분석 고도화

목표:

- 후보군 저장을 염두에 두고 분석 신호를 풍부하게 만들기

작업:

- 하이라이트 후보군 저장 구조 설계
- context label 추가
- signal payload 설계
- 설명 문구보다 내부 맥락 신호 저장 우선

### Phase 2. 사용자 활동 수집

목표:

- 개인화에 필요한 행동 데이터 쌓기

작업:

- 하이라이트 클릭/저장/핀/무시 이벤트 저장
- owner 기준 VOD library 구성

### Phase 3. 선호 설정

목표:

- 사용자가 직접 취향을 선택할 수 있게 하기

작업:

- 선호 태그 설정 UI
- 선호 길이, 선호 반응 유형 저장

### Phase 4. 개인화 랭킹

목표:

- 같은 영상이라도 사용자별 다른 추천 순서를 제공

작업:

- personalized score 계산
- 기본 추천/내 취향 추천 토글

### Phase 5. 마이페이지 연결

목표:

- 영상 번호만 기억하는 것이 아니라, 나중에 다시 들어와 바로 결과를 볼 수 있게 만들기

작업:

- 내 분석 영상 목록
- 저장한 후보 목록
- 최근 본 VOD 바로가기

## 10. 우선 구현 추천

가장 먼저 하는 것이 좋은 것:

1. `user_vod_library`
2. `user_vod_activity`
3. 하이라이트 후보 클릭/저장 이벤트 수집
4. 마이페이지의 "최근 분석한 VOD" 목록

이유:

- 개인화는 취향 모델보다 데이터가 먼저 쌓여야 의미가 생기기 때문입니다.
- 지금 단계에서는 "선호 점수 계산"보다 "행동 데이터 수집 구조"를 먼저 만드는 것이 더 중요합니다.

## 11. 성공 기준

- 같은 VOD를 나중에 다시 열었을 때 바로 결과를 볼 수 있다
- 마이페이지에서 최근 분석한 영상과 저장한 후보를 볼 수 있다
- 사용자 취향 설정 후 추천 순서가 달라진다
- 개인화가 공용 분석 품질을 해치지 않는다

## 12. 메모

현재 코드 기준으로는 `ownerId`와 `videoNo`를 결합한 사용자별 레이어가 아직 없습니다.
즉 다음 구현의 핵심은 "새 분석을 더 많이 하는 것"이 아니라 "이미 분석된 결과를 사용자별로 다시 사용할 수 있게 저장 구조를 바꾸는 것"입니다.
