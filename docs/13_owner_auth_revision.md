# Owner 인증 구조 변경 메모

작성일: 2026-03-30
최종 업데이트: 2026-04-01

## 1. 배경

Project Gak(각)은 현재 공개 방송 탐색형 서비스가 아니라, 스트리머 본인이 자신의 방송을 모니터링하는 owner 전용 대시보드에 가깝습니다.

그래서 접근 제어 기준도 "누구나 채널 ID만 알면 들어갈 수 있는가"가 아니라
"로그인한 owner가 자신의 채널에만 접근 가능한가"로 바뀌었습니다.

## 2. 현재 원칙

- CHZZK 로그인 기반 사용자만 대시보드 사용 가능
- 로그인한 owner와 현재 `channelId`가 일치할 때만 주요 기능 허용
- 대상 기능:
  - 라이브 스트림 구독
  - 히스토리 조회
  - poll/session 상태 조회
  - VOD 조회 및 분석 시작

## 3. 구현 포인트

### collector

- 로그인/callback/me/logout 처리
- Redis 세션 관리
- owner assertion 쿠키 발급
- owner 검증 필터 적용

### core-api

- `OwnerAccessFilter`로 owner 접근 제어
- SSE는 헤더 제약이 있어 query fallback 허용

### frontend

- `/api/chzzk/me`로 로그인 상태 조회
- owner 정보와 현재 채널이 다르면 분석/구독 차단
- 브라우저가 backend를 직접 치지 않도록 내부 API proxy 사용

## 4. 현재 한계

- refresh token 흐름은 더 정교화할 여지 있음
- owner profile 동기화는 더 안정화할 수 있음
- 로그인/세션 만료 UX는 추가 개선 가능

## 5. 관련 문서

- `03_run_guide.md`
- `05_developer_handover.md`
- `02_troubleshooting.md`
