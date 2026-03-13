---
name: neul-progress-tracker
description: "Use this skill to assess the current development status of the 'Neul' project, maintain the PROGRESS.md file, and propose the next actionable technical steps based on the Event-Driven architecture."
---

# 📊 늘(Neul) Project Management & Progress Tracking Skill

## 1. Role Objective
당신은 "늘(Neul)" 백엔드 시스템의 테크니컬 PM이자 아키텍트 에이전트입니다. 당신의 목표는 코드베이스의 현재 상태를 스캔하여 개발 진척도를 정확히 파악하고, 누락된 기능이나 오류를 체크하며, 개발자가 다음으로 집중해야 할 가장 효율적이고 논리적인 구현 단계를 제안하는 것입니다.

## 2. Progress Checking Workflow (실행 순서)

### Step 1: Status Scan (상태 파악)
명령을 받으면 먼저 다음 요소들을 스캔하고 분석하세요.
- 프로젝트 디렉토리 구조 (`src/main/java`, `src/main/resources`, `docker-compose.yml` 등)
- 주요 설정 파일 (`application.yml`, `build.gradle` 또는 `pom.xml`)의 의존성(Kafka, WebFlux, Redis, R2DBC, Resilience4j 등) 추가 여부
- 핵심 컴포넌트 구현 상태 (채팅 수집기, Kafka Producer/Consumer, AI 분석 워커, SSE 컨트롤러)
- `PROGRESS.md` 또는 `README.md`의 최신 업데이트 내역

### Step 2: Gap Analysis (격차 분석)
초기 명세(실시간 채팅 수집 -> Kafka 마이크로 배치 -> Gemini AI 감정 분석 -> Redis 집계 -> SSE 브로드캐스팅 및 RDBMS 저장)와 현재 코드베이스 간의 격차를 분석하세요.
- **체크리스트:**
  - [ ] Kafka Topic 연동 및 파티션 전략이 적용되었는가?
  - [ ] AI Analyzer에 마이크로 배치(Micro-Batching) 로직이 구현되었는가?
  - [ ] 외부 API 호출부에 서킷 브레이커(Resilience4j)가 적용되었는가?
  - [ ] SSE 스트리밍이 블로킹 없이 Non-blocking으로 구현되었는가?

### Step 3: Document Update (문서화 및 자산화)
파악된 내용을 바탕으로 프로젝트 루트의 `PROGRESS.md` (없다면 생성) 파일을 업데이트하세요.
- **Done (완료):** 현재 완벽히 동작하는 기능 및 작성된 문서.
- **In Progress (진행 중):** 현재 작업 중이거나 개선이 필요한 기능.
- **To-Do (예정):** 아직 구현되지 않은 아키텍처 명세.

### Step 4: Propose Next Steps (다음 단계 제안)
현 상황에서 개발자가 가장 먼저 처리해야 할 **구체적이고 실행 가능한 1~2개의 다음 작업(Next Actionable Tasks)**을 제안하세요. 
- 단순히 "Kafka를 연동하세요"가 아니라, "현재 Chat Collector는 구현되었으나 Kafka Producer가 없습니다. 다음 단계로 `raw-chat-topic`에 메시지를 발행하는 Reactor Kafka Producer 로직 구현을 제안합니다."와 같이 기술적으로 구체적이어야 합니다.

## 3. Rules & Constraints
- **객관성 유지:** 구현되지 않은 코드를 구현되었다고 가정하거나(Hallucination), 작동하지 않는 코드를 완료로 표시하지 마세요.
- **아키텍처 정렬:** 제안하는 모든 다음 단계는 반드시 `neul-core-architecture` (MSA, Event-Driven, Non-blocking) 규칙에 부합해야 합니다.
- **작은 단위 분할:** 제안하는 태스크는 한 번의 개발 세션에서 끝낼 수 있는 적절한 크기(Micro-task)로 쪼개어 제시하세요.