# 늘(Neul) 구현 체크리스트

> 기준일: 2026-03-26
> 기준 문서: `docs/00_PROJECT_MASTER_HISTORY.md`, `docs/05_developer_handover.md`, `docs/06_testing_strategy.md`, `docs/09_evolution_roadmap.md`, `03_prev.md`

이 문서는 문서상 계획과 현재 코드베이스를 대조해서 "실제로 아직 구현하거나 다듬어야 하는 일"만 추린 작업 체크리스트입니다.

## 1. 지금 우선순위

- [ ] E2E 테스트 체계 완성
- [ ] Rust optimizer 실구현
- [ ] 하이브리드 LLM 분석 추가
- [ ] 문서/엔드포인트/배치 단위 정합성 정리
- [ ] VOD/시각화/커뮤니티 기능의 완성도 점검

## 2. 체크리스트

### A. 테스트 및 검증

- [x] `backend/core-api` Testcontainers 기반 베이스 테스트 클래스 존재
- [x] `FullPipelineE2ETest` 초안 존재
- [x] `collector`용 `MockChzzkServer` 존재
- [x] 프론트 `frontend/e2e/dashboard.spec.ts` 초안 존재
- [ ] `HighlightE2ETest` 추가 및 안정화
- [ ] Redis/DB/Kafka를 포함한 E2E 테스트 실제 실행 검증
- [ ] Playwright에서 실데이터 주입 기반으로 차트/하이라이트 변화 검증
- [ ] CI에서 자동 실행하도록 워크플로우 연결

### B. Analyzer / AI

- [x] `ChatOptimizer` 포트 구조 존재
- [x] `JavaChatOptimizer` 구현 존재
- [x] `RustChatOptimizer` 스텁 존재
- [ ] `RustChatOptimizer` JNI 직렬화/역직렬화 구현
- [ ] Rust 네이티브 라이브러리 빌드 파이프라인 연결
- [ ] Java/Rust 기능 동질성 테스트 작성
- [ ] 성능 벤치마크 결과를 `docs/08_performance_migration_log.md`에 기록
- [ ] Ollama 외 보조 LLM 기반 요약/맥락 분석 추가

### C. Core API / 실시간 기능

- [x] 실시간 SSE 스트림과 하이라이트 감지 로직 존재
- [x] 투표 관련 API/Redis 집계 기능 존재
- [x] VOD 하이라이트 저장 소비자와 조회 API 존재
- [ ] 하이라이트 생성이 외부 API 실패에도 안정적으로 동작하는지 검증
- [ ] SSE 경로와 문서 표기를 하나로 정리
- [ ] 하이라이트/VOD/투표 기능의 통합 회귀 테스트 추가

### D. Frontend

- [x] 라이브 대시보드 페이지 존재
- [x] `EmotionHeatmap`, `MoodGauge`, `KeywordBubbleChart`, `VodHighlightBoard` 컴포넌트 존재
- [ ] 실제 SSE 이벤트와 각 위젯의 연결 상태 검증
- [ ] 투표 참여자 히스토리/운영 흐름 UX 점검
- [ ] VOD 탭과 백엔드 분석 트리거 연결 검증

### E. 문서 정리

- [ ] 배치 단위 설명이 `1분`과 `2초`로 혼재된 부분 정리
- [ ] SSE 엔드포인트 표기를 실제 코드 기준으로 통일
- [ ] 완료된 기능과 계획 중 기능을 분리해서 문서 갱신

## 3. 바로 진행 중인 작업

- [x] 하이라이트 E2E 테스트 초안 추가
- [x] 하이라이트 썸네일 조회를 실패 내성 있게 보강
- [x] 새 테스트 소스 컴파일 확인
- [x] Docker 없는 환경에서 E2E 테스트가 실패 대신 skip 되도록 조정
- [ ] Docker 환경에서 하이라이트 E2E 실제 실행 확인

## 4. 다음 추천 순서

1. 하이라이트 E2E를 실행 가능 상태로 마무리
2. 프론트 Playwright를 실제 이벤트 검증까지 확장
3. Rust optimizer 구현 범위를 잘라 1차 직렬화 어댑터부터 연결
4. 이후 하이브리드 LLM 요약 기능 착수
