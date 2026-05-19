# Project Gak (각) 개발자 핸드오버

최종 업데이트: 2026-05-16

이 문서는 기존 `05_developer_handover.md`와 `11_spec_handover_report.md`를 합친 최신 핸드오버 문서입니다.

## 1. 프로젝트 한 줄 설명

Project Gak(각)은 스트리머 본인이 자신의 방송과 다시보기를 빠르게 돌아보면서, 실시간 반응과 편집 후보 구간을 확인할 수 있게 돕는 분석 대시보드입니다.

## 2. 현재 제품 정의

초기에는 공개 방송 탐색형에 가까운 문서도 있었지만, 현재 기준 제품 정의는 아래와 같습니다.

- 대상 사용자: 스트리머 본인
- 핵심 화면: owner 전용 채널 대시보드
- 핵심 가치:
  - 라이브 채팅 반응 파악
  - 다시보기에서 편집 후보 구간 빠르게 찾기

## 3. 서비스 역할

### frontend

- 로그인 버튼, owner 대시보드 UI
- VOD 조회/분석/결과 표시
- 브라우저가 backend를 직접 치지 않도록 내부 API proxy 제공

### collector

- CHZZK 로그인 및 세션 처리
- 라이브 채팅 수집
- VOD 채팅 크롤링
- VOD 분석 상태 관리

### analyzer

- 라이브 채팅 분석
- VOD 편집 후보 계산
- completion 이벤트 발행

### core-api

- 분석 결과 저장/조회
- SSE 제공
- owner 접근 제어
- Flyway 기반 스키마 관리

## 4. 현재 중요 기능

### 4-1. owner 인증 흐름

- Next proxy -> collector 로그인 시작
- callback 후 Redis 세션 및 owner assertion 쿠키 발급
- `/api/chzzk/me`로 로그인 상태 확인
- 본인 채널에만 대시보드 접근 허용

관련 문서:

- `13_owner_auth_revision.md`

### 4-2. 라이브 채팅 분석

- collector가 채팅 수집
- analyzer가 감정/반응 데이터 계산
- core-api가 저장 및 SSE 송신
- frontend 대시보드가 스트림 구독

### 4-3. VOD 조회/분석 흐름

- 조회: VOD 존재 여부와 메타데이터 확인
- 분석 시작: 실제 백엔드 작업 시작
- 상태: `REQUESTED / CRAWLING / ANALYZING / COMPLETED / FAILED`
- 결과:
  - timeline
  - editorial highlight cards

### 4-4. 편집 후보 중심 하이라이트

최근 변경점:

- 하이라이트는 더 이상 단일 감정 점수만 보여주지 않음
- 내부 점수:
  - `intensityScore`
  - `transitionScore`
  - `editabilityScore`
- 사용자 UI:
  - `추천 강도`
  - `추천 이유`
  - `대표 채팅`

관련 문서:

- `15_emotion_analysis_experiment_plan.md`

## 5. DB 및 스키마 관리

현재 기준:

- PostgreSQL은 Docker 기반 사용 권장
- core-api 부팅 시 Flyway가 스키마를 자동 반영

중요 포인트:

- 더 이상 `schema.sql` 수동 동기화 전제를 기준 문서로 보지 않음
- 새 테이블/컬럼은 반드시 Flyway migration으로 반영

## 6. 자주 보는 파일

### frontend

- `frontend/src/app/channels/[channelId]/page.tsx`
- `frontend/src/components/VodHighlightBoard.tsx`

### collector

- `backend/collector/src/main/java/com/gak/collector/controller/VodCollectorController.java`
- `backend/collector/src/main/java/com/gak/collector/service/VodChatCrawlerService.java`
- `backend/collector/src/main/java/com/gak/collector/service/VodAnalysisStatusService.java`

### analyzer

- `backend/analyzer/src/main/java/com/gak/analyzer/service/VodHighlightAnalyzer.java`

### core-api

- `backend/core-api/src/main/java/com/gak/core_api/domain/chat/controller/VodController.java`
- `backend/core-api/src/main/resources/db/migration`

## 7. 현재 남아 있는 주의점

- VOD 분석 완료 상태는 completion 이벤트와 fallback 보정으로 안정화했지만, 서비스 기동 순서가 꼬이면 여전히 관찰이 필요합니다.
- timeline은 fallback이 있어 화면이 완전히 비지는 않지만, 정확한 전체 흐름을 보려면 timeline 저장이 정상이어야 합니다.
- VOD 동시성 제한은 **구현 완료** (사용자별 1건 / 전체 3건 Redis 기반). 상세: [14_vod_concurrency_plan.md](14_vod_concurrency_plan.md)
- 편집 후보 점수(`intensityScore`, `transitionScore`, `editabilityScore`)는 현재 코드에 반영되어 있으며, LLM 리뷰(상위 12개 후보)를 통한 최종 선별까지 동작 중입니다.

## 8. 다음에 이어서 보기 좋은 문서

1. `03_run_guide.md`
2. `02_troubleshooting.md`
3. `13_owner_auth_revision.md`
4. `14_vod_concurrency_plan.md`
5. `15_emotion_analysis_experiment_plan.md`
