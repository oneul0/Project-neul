# Progress Sprit Log

이 문서는 "늘(Neul)" 프로젝트의 개발 진행 상황을 스프린트 단위로 기록합니다.

---

## [Sprint 1] 고성능 데이터 파이프라인 구축 (2026-03-13 ~ 2026-03-14)

### 완료된 작업
- **Backend - Collector:**
  - `NidChatCollector` 구현 (내부 웹소켓 연동).
  - 1분 단위 메시지 배치(Batching) 로직 적용.
  - `NativeBridge` JNI 인터페이스 설계 및 Java Fallback 구현.
- **Backend - Analyzer:**
  - `ChatAnalysisProcessor` 리팩토링 (Batch JSON 소비 및 벌크 분석).
  - Ollama LLM 연동 최적화.
- **Backend - Core API:**
  - `ChatStreamService` SSE 푸시 로직 구현.
  - PostgreSQL 및 Redis 연동 (실시간 통계 및 로그 저장).
- **Backend - Common:**
  - DTO 통합 (`RawChatMessage`, `RawChatBatch`, `AnalyzedChatMessage`, `Emotion`).
- **Frontend:**
  - 채널 대시보드 UI 기초 설계 및 더미 데이터 연동 확인.
  - 채널 ID 직접 검색 기능 추가.

### 주요 구현 포인트
- `com.neul.collector.service.NidChatCollector`: Reactive Stream을 활용한 윈도우 기반 배치 처리.
- `com.neul.common.dto.RawChatBatch`: 1분간 수집된 메시지를 담는 벌크 전송 객체.
- `docs/performance_migration_log.md`: 향후 Rust 최적화를 위한 벤치마킹 체계 구축.

### 미해결 이슈 / 블로커
- **Frontend Sync:** UI 대시보드에서 1분 배칭 데이터를 어떻게 시각적으로 부드럽게 보여줄지에 대한 UX 고민 필요.
- **Native Impl:** 실제 Rust(.so/.dll) 모듈 구현 및 JNI 실제 로딩 테스트 대기 중.

### 다음 단계
1. **Frontend UI 고도화**: 1분 배치 데이터에 맞춘 실시간 차트 구현.
2. **Rust Prototype**: `NativeBridge`를 통한 간단한 Rust 문자열 처리 모듈 연동.
3. **Stress Test**: 수만 건의 채팅 환경에서 1분 배칭이 정상 작동하는지 부하 테스트 진행.
