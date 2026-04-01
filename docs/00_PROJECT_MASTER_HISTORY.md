# Project Neul 문서 인덱스 및 변경 이력

최종 업데이트: 2026-04-01

이 문서는 현재 프로젝트 문서의 진입점입니다.
이전에는 이력, 핸드오버, 트러블슈팅 로그, 구현 체크리스트가 여러 파일로 흩어져 있었고 일부 문서는 중복되거나 최신 구조와 맞지 않았습니다.
2026-04-01 기준으로 문서 구조를 정리했고, 중복 문서는 병합했습니다.

## 현재 문서 구조

| 번호 | 문서 | 용도 |
|---|---|---|
| 00 | `00_PROJECT_MASTER_HISTORY.md` | 문서 인덱스, 큰 흐름의 변경 이력 |
| 01 | `01_ADR.md` | 아키텍처 결정 기록 |
| 02 | `02_troubleshooting.md` | 통합 트러블슈팅 가이드 및 이력 |
| 03 | `03_run_guide.md` | 현재 기준 실행 순서와 운영 체크 |
| 04 | `04_technical_concepts.md` | 기술 개념 설명용 문서 |
| 05 | `05_developer_handover.md` | 현재 구조 기준 핸드오버 문서 |
| 06 | `06_testing_strategy.md` | 테스트 전략 및 E2E 방향 |
| 07 | `07_native_optimization_guide.md` | Java/Rust 최적화 방향 |
| 08 | `08_performance_migration_log.md` | 성능 이전 로그 템플릿 |
| 09 | `09_evolution_roadmap.md` | 로드맵 + 구현 체크리스트 통합본 |
| 12 | `12_neul_v2_implementation_plan.md` | v2 장기 구현 계획 |
| 13 | `13_owner_auth_revision.md` | owner 인증 구조 변경 메모 |
| 14 | `14_vod_concurrency_plan.md` | VOD 동시성 및 안정성 계획 |
| 15 | `15_emotion_analysis_experiment_plan.md` | 편집 후보 중심 감정 분석 실험 계획 |

## 이번 정리에서 병합/정리된 문서

- `02_troubleshooting_log.md` → `02_troubleshooting.md`로 병합
- `06_troubleshooting_guide.md` → `02_troubleshooting.md`로 병합
- `11_spec_handover_report.md` → `05_developer_handover.md`로 병합
- `10_implementation_checklist.md` → `09_evolution_roadmap.md`로 병합

## 운영 기준 현재 상태

- 인증 구조는 공개 탐색형이 아니라 owner 전용 대시보드 기준입니다.
- CHZZK 로그인은 `frontend -> Next API proxy -> collector` 흐름으로 처리합니다.
- 세션 저장은 Redis 기반입니다.
- core-api 스키마는 `schema.sql` 수동 반영이 아니라 Flyway 마이그레이션으로 관리합니다.
- VOD 분석은 `조회`와 `분석 시작`이 분리되어 있습니다.
- VOD 분석 결과는 단순 감정 점수보다 "편집 후보 탐색" 관점으로 고도화 중입니다.

## 날짜별 기록

### 2026-02 ~ 2026-03 중순

- collector / analyzer / core-api 3개 백엔드 서비스와 frontend 기본 골격 구성
- Kafka, Redis, PostgreSQL 기반 실시간 파이프라인 구성
- CHZZK 실시간 채팅 수집, 분석, SSE 스트리밍 기본 구조 정착
- 감정 분석, 하이라이트 감지, Redis 집계, 대시보드 시각화 초안 구현

### 2026-03-30

- owner 인증 요구사항 반영 시작
- 공개 채널 탐색형 화면에서 "로그인한 스트리머 본인 채널 중심" 구조로 전환
- collector/core-api/frontend 모두 owner assertion 기준으로 접근 제어 정리

### 2026-03-31

- CHZZK 로그인 흐름과 owner 대시보드 UX 정리
- 브라우저가 backend를 직접 치지 않도록 Next API proxy 도입
- VOD 조회와 분석 시작을 분리
- VOD 메타데이터 카드, 분석 상태, 하이라이트 보드 구성
- mock chat 주입 API 추가로 라이브 방송 없이도 파이프라인 검증 가능하게 변경
- `vod_timeline_points` 추가 및 이후 Flyway 전환 필요성 명확화
- VOD 분석 완료 이벤트와 collector 상태 보정 흐름 정리

### 2026-04-01

- VOD 하이라이트를 "감정 점수 출력"이 아니라 "편집 후보 탐색" 기준으로 재정의
- `intensityScore`, `transitionScore`, `editabilityScore`, `reactionLabel`, `reasonSummary` 도입
- frontend에서 내부 점수명을 직접 노출하지 않고 `추천 강도`, `추천 이유`, `대표 채팅` 중심으로 단순화
- 문서 구조 정리:
  - 중복 로그/체크리스트/핸드오버 문서 병합
  - 오래된 `schema.sql`, 공개 대시보드 전제, 예전 배치 설명 등 최신화

## 문서 사용 우선순위

새로 합류하거나 전체 구조를 이해할 때:

1. `00_PROJECT_MASTER_HISTORY.md`
2. `05_developer_handover.md`
3. `03_run_guide.md`
4. `02_troubleshooting.md`
5. 필요 시 `13`, `14`, `15`

기술적 판단의 근거를 볼 때:

1. `01_ADR.md`
2. `04_technical_concepts.md`
3. `07_native_optimization_guide.md`
4. `08_performance_migration_log.md`
