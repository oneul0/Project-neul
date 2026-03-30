# 늘(neul) 인증 요구사항 반영 메모

작성일: 2026-03-30

## 1. 요구사항 재정의

이 프로젝트는 공개형 방송 탐색 대시보드가 아니라, 스트리머가 자신의 방송만 모니터링하는 전용 대시보드다.

따라서 접근 제어 기준은 아래처럼 바뀐다.

- 치지직 로그인으로 본인 방송 여부를 인증한 사용자만 사용 가능
- 인증된 스트리머는 자신의 `channelId`에 대해서만 수집/조회/스트림 구독 가능
- 타 채널에 대한 실시간 분석, 히스토리 조회, 투표 상태 조회는 모두 차단

## 2. 현재 구현 기준에서 수정이 필요한 부분

기존 구현은 collector의 subscribe API만 소유자 헤더를 검사하고 있었다.

이 상태의 문제점:

- `collector`만 막혀 있고 `core-api`의 stream/history/poll/v2 state는 공개형 접근
- frontend가 `ownerChannelId || channelId` 기본값으로 우회 가능
- v2 SSE/state 조회도 소유자 인증과 무관하게 접근 가능
- 홈 화면과 채널 화면이 여전히 “누구나 채널 ID만 알면 들어갈 수 있는 분석 화면”처럼 보임

## 3. 이번에 반영한 수정

### backend

- `collector`
  - 기존 `OwnerValidationFilter` 유지
  - subscribe/unsubscribe 시 owner assertion 쿠키를 우선 검증
  - fallback으로 `X-Chzzk-Owner-Id`와 `channelId` 일치 여부 검사
  - `/api/v1/chzzk/login`
  - `/api/v1/chzzk/callback`
  - `/api/v1/chzzk/me`
  - `/api/v1/chzzk/logout`
  - callback 성공 시 로그인 세션 쿠키와 서명된 owner assertion 쿠키 발급

- `core-api`
  - `OwnerAccessFilter` 추가
  - 아래 경로는 소유자 검증 필수
    - `/api/v1/stream/{roomId}`
    - `/api/v1/stream/{roomId}/history`
    - `/api/v1/poll/{roomId}/...`
    - `/api/v2/state/{roomId}`
    - `/api/v2/stream/{roomId}`
  - owner assertion 쿠키를 우선 검증
  - 일반 fetch는 `X-Chzzk-Owner-Id` 헤더 fallback 허용
  - SSE는 헤더 전송 제약이 있어 `?ownerId=` query param fallback 허용

### frontend

- `ownerAuth` 유틸 추가
  - owner 헤더 생성
  - SSE용 owner query 부착

- 채널 대시보드 페이지 수정
  - 수동 owner 입력 우회 제거
  - `/api/v1/chzzk/me`로 로그인 상태 조회
  - 로그인 버튼/로그아웃 버튼 추가
  - 로그인된 owner id와 현재 `channelId`가 다르면 스트림 연결/세션 시작 차단
  - v1 stream, v2 stream, poll, history 요청 모두 owner id 첨부
  - cross-origin cookie 전송을 위해 `credentials: include`, `EventSource(..., { withCredentials: true })` 반영

## 4. 현재 인증 구조의 한계

지금 반영한 것은 “OAuth 로그인 시작 + owner assertion 기반 접근 제어” 단계다.

즉, 아래는 아직 미구현이다.

- refresh token 갱신
- collector/core-api 사이의 인증 완전 일원화
- 홈 화면의 로그인 UX 정리
- 로그인 세션 기반으로 수집 시작을 자동화하는 흐름
- OAuth profile 스키마가 실제 응답과 다를 경우 추가 매핑 보정

현재는 owner assertion 쿠키와 `/me` 응답을 함께 사용해 접근을 제한한다.

## 5. 수정된 구현 우선순위

### Phase A. 소유자 접근 통일

목표:
- collector, core-api, frontend가 모두 같은 owner 검증 규칙을 사용

상태:
- 반영 완료

세부:
- subscribe 보호
- v1/v2 stream 보호
- poll/history/state 보호
- 프론트 owner id 전달 통일

### Phase B. CHZZK OAuth 안정화

목표:
- 로그인 흐름을 안정적으로 운영 가능한 수준으로 고도화

작업:
- token refresh
- session store 영속화 또는 Redis 이전
- CHZZK profile 응답 필드 보정
- 로그인 실패/만료 UX 정리

### Phase C. 스트리머 전용 홈 화면 개편

목표:
- 공개 방송 탐색 화면에서 “내 방송 분석 시작” 화면으로 전환

작업:
- 전체 live list 중심 UI 축소 또는 제거
- 로그인 상태, 내 채널 정보, 최근 세션 중심 화면으로 재구성
- “내 채널 대시보드 바로가기” 진입 흐름 제공

### Phase D. v2 품질 고도화

목표:
- 인증 구조 위에서 실사용 품질 향상

작업:
- Trust Score 튜닝
- Context Agent 임베딩 기반 고도화
- Narrative Briefing LLM 연동
- aggregate state Redis 일원화

## 6. 남은 블로커

- 실제 CHZZK OAuth 연동이 아직 없음
- owner channel id를 백엔드 세션에서 자동 판별하지 못함
- 홈 화면이 여전히 공개형 탐색 UX를 일부 유지함

## 7. 다음 추천 작업

1. `collector`에 실제 CHZZK login/callback 구현
2. `core-api` 또는 `collector`에 현재 로그인한 owner profile 조회 API 추가
3. frontend 홈 화면을 “내 방송 전용 진입” UX로 정리
4. 수동 owner id 입력 UI 제거
