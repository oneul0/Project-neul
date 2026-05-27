#!/usr/bin/env python3
"""
샘플 워크플로우 로그 생성기.

실제 프로젝트 코드베이스의 파일명·클래스명·메서드명을 사용해
그럴듯한 workflow_logs/ 항목을 만든다.

실행:
    python scripts/generate_sample_logs.py
"""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path

BASE = Path(__file__).parent.parent
LOG_ROOT = BASE / "workflow_logs"

# ─────────────────────────────────────────────
#  공통 헬퍼
# ─────────────────────────────────────────────

def ts(offset: float = 0.0) -> float:
    return 1748220000.0 + offset  # 2026-05-26 기준 고정

def write_jsonl(path: Path, events: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for e in events:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")

def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# ─────────────────────────────────────────────
#  RUN 1 — LLM 출력 불확실성 3계층 방어 구현
# ─────────────────────────────────────────────

def run_llm_guardrail() -> None:
    run_id = "a3f7b1c2"
    run_dir = LOG_ROOT / f"20260521_143022_{run_id}"

    task = (
        "OllamaAnalyzerService.validateScores()가 합계 이탈·키 불일치 케이스를 처리하지 못해 "
        "오염된 감정 점수가 VodHighlightAnalyzer까지 흘러가는 문제를 수정하고 싶다. "
        "extractJsonText()와 validateScores()에 3계층 방어 로직을 추가해줘."
    )

    researcher_system = """\
당신은 코드베이스 탐색 전문가입니다.

## 역할
주어진 작업을 이해하기 위해 코드베이스를 **읽기 전용**으로 탐색하고,
사실 기반의 상세 보고서를 작성합니다.

## 규칙
1. 파일을 수정하거나 생성하지 않습니다.
2. 가능한 한 구체적인 파일 경로, 클래스명, 메서드명, 줄 번호를 포함합니다.
3. 불확실한 내용은 "확인 필요"로 표시합니다.
4. 의견이나 제안 없이 **사실만** 보고합니다."""

    researcher_user = f"다음 작업과 관련된 코드베이스를 탐색하고 보고서를 작성해주세요:\n\n{task}"

    researcher_report = """\
# Researcher 보고서

## 1. 관련 파일 목록
- `backend/analyzer/src/main/java/com/gak/analyzer/service/OllamaAnalyzerService.java`: LLM 호출·응답 파싱·검증 핵심 서비스
- `backend/analyzer/src/main/java/com/gak/analyzer/service/VodHighlightAnalyzer.java`: 감정 점수를 받아 intensityScore / editabilityScore 계산
- `backend/analyzer/src/test/java/com/gak/analyzer/service/OllamaAnalyzerServiceTest.java`: 기존 단위 테스트
- `backend/analyzer/src/main/resources/prompts/ollama-batch-system.txt`: LLM 시스템 프롬프트 템플릿

## 2. 핵심 구조
- `OllamaAnalyzerService.validateScores(Map<String,Double>)` (238줄): 7개 키 완결성 확인 + clamp — **합계 재정규화 없음**
- `OllamaAnalyzerService.extractJsonText(String)` (392줄): 마크다운 코드펜스 제거 — **LLM이 중첩 펜스 반환 시 실패**
- `OllamaAnalyzerService.analyzeBatch(List<CompressedChat>)` (77줄): Semaphore(1) + @CircuitBreaker("geminiApi")
- `VodHighlightAnalyzer.computeWindowStats()`: laughCount / hypeCount 등 감정 레이블 직접 참조

## 3. 의존 관계
- `OllamaAnalyzerService` → (Mono) → `VodHighlightAnalyzer.analyzeVod()` → `intensityScore` 산출
- `validateScores` 반환값이 `parseResponse` → `AnalyzedChatMessage.emotions` 필드로 직접 저장됨
- `VodHighlightAnalyzer`는 emotions 합계가 1.0이라고 가정하고 비율 계산 중

## 4. 변경 영향 범위
- **직접**: `OllamaAnalyzerService.validateScores`, `extractJsonText`
- **간접**: `parseResponse` (반환 타입 변경 없음, 내부 로직 영향)
- **테스트**: `OllamaAnalyzerServiceTest` — `validateScores` 케이스 추가 필요

## 5. 주의사항
- `validateScores`는 현재 clamp만 하고 **재정규화를 하지 않음** — 합계가 1.5인 경우 그대로 통과
- `extractJsonText`는 ` ```json ` 한 겹만 제거 — 중첩 펜스나 trailing whitespace 미처리
- `fallbackAnalyzeBatch`는 모든 감정을 NEUTRAL로 반환하는데, validateScores 실패 시 이 경로와 구분 불가"""

    planner_report = """\
# Planner 구현 계획서

## 1. 목표 요약
OllamaAnalyzerService의 LLM 응답 처리 경로에 3계층 방어를 완성해
오염된 감정 점수가 VodHighlightAnalyzer로 흘러가는 경로를 차단한다.

## 2. 선택된 접근법
Layer 2(extractJsonText)와 Layer 3(validateScores)을 강화하는 방향을 채택.
프롬프트 수정(Layer 1)은 이미 적용돼 있으므로 범위 외.

## 3. 구현 단계

### 단계 1: extractJsonText 강화
- 대상 파일: `OllamaAnalyzerService.java` (392줄)
- 변경 내용: 정규식 `(?s)```(?:json)?\\s*(.*?)\\s*``` ` 으로 중첩·trailing 처리
- 검증 방법: 단위 테스트 — 중첩 펜스 / 순수 JSON / 빈 문자열 3케이스

### 단계 2: validateScores 재정규화 추가
- 대상 파일: `OllamaAnalyzerService.java` (238줄)
- 변경 내용: 합계 < 0.001 → NEUTRAL 강제 / 합계 > 0 → 각 값 ÷ 합계
- 검증 방법: 합계 1.5 입력 → 반환값 합계 1.0 확인

### 단계 3: 테스트 추가
- 대상 파일: `OllamaAnalyzerServiceTest.java`
- 추가 케이스: 합계 초과 / 키 누락 / 마크다운 래핑 / zero-score

## 4. 위험 요소
| 위험 | 가능성 | 완화 전략 |
|------|--------|----------|
| 재정규화로 기존 점수 분포 변동 | 낮음 | 기존 테스트가 정규화 결과 검증 중 |
| extractJsonText 정규식 성능 | 낮음 | 배치 크기 최대 30개, 영향 미미 |"""

    reviewer_report = """\
# Reviewer 리뷰 보고서

## 최종 판정
[x] ✅ 승인 — 계획대로 진행 가능

## 리스크 분석
| 항목 | 리스크 | 설명 |
|------|--------|------|
| validateScores 재정규화 | 🟢 LOW | 기존 clamp 이후 적용, 기존 정상 케이스 영향 없음 |
| extractJsonText 정규식 | 🟢 LOW | 단순 문자열 처리, 사이드이펙트 없음 |
| OllamaAnalyzerServiceTest 커버리지 | 🟡 MEDIUM | 현재 happy-path 위주 — 추가 케이스 필요 |

## 계획 검증 결과
### ✅ 정확한 부분
- `validateScores` 238줄 위치 확인 — 계획과 일치
- `extractJsonText` 392줄 위치 확인 — 계획과 일치
- `VodHighlightAnalyzer`가 emotions 합계 1.0 가정하는 부분 실제 코드에서 확인

### ⚠️ 주의 필요
- `fallbackAnalyzeBatch`도 `createNeutralScores()`를 호출하는데, 이 경로는 validateScores를 거치지 않음 — 별도 확인 권장

## 결론
3계층 방어 로직 강화는 영향 범위가 `OllamaAnalyzerService` 내부로 한정되고,
VodHighlightAnalyzer 인터페이스 변경 없음. 승인."""

    # events.jsonl
    events: list[dict] = [
        {"event": "workflow_start", "run_id": run_id, "task": task,
         "project_dir": "/Users/hoon/Desktop/Projects/project-neul", "timestamp": ts(0)},

        # Researcher
        {"event": "agent_start", "run_id": run_id, "agent_name": "Researcher",
         "agent_role": "researcher", "model": "claude-opus-4-7",
         "system_prompt": researcher_system, "user_message": researcher_user, "timestamp": ts(1)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 0,
         "messages_snapshot": [{"role": "user", "content_preview": researcher_user[:200]}],
         "stop_reason": "tool_use", "input_tokens": 1842, "output_tokens": 312,
         "duration_seconds": 3.21, "timestamp": ts(4.2)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 0,
         "tool_use_id": "tu_001", "tool_name": "list_directory",
         "tool_input": {"path": "backend/analyzer/src/main/java/com/gak/analyzer/service", "recursive": False},
         "tool_result": "OllamaAnalyzerService.java\nVodHighlightAnalyzer.java\nChatAnalysisProcessor.java",
         "result_truncated": False, "duration_seconds": 0.04, "error": "", "timestamp": ts(7.5)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 1,
         "messages_snapshot": [{"role": "user", "content_preview": researcher_user[:200]},
                                {"role": "assistant", "content_blocks": ["[tool_use: list_directory]"]},
                                {"role": "user", "content_blocks": ["[tool_result: OllamaAnalyzer...]"]}],
         "stop_reason": "tool_use", "input_tokens": 2104, "output_tokens": 287,
         "duration_seconds": 4.07, "timestamp": ts(11.6)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 1,
         "tool_use_id": "tu_002", "tool_name": "read_file",
         "tool_input": {"path": "backend/analyzer/src/main/java/com/gak/analyzer/service/OllamaAnalyzerService.java",
                        "line_start": 230, "line_end": 280},
         "tool_result": "private Map<String, Double> validateScores(Map<String, Double> raw) {\n    Map<String,Double> result = new HashMap<>();\n    for (String key : VALID_EMOTIONS) {\n        result.put(key, Math.max(0.0, Math.min(1.0, raw.getOrDefault(key, 0.0))));\n    }\n    return result;\n}",
         "result_truncated": False, "duration_seconds": 0.03, "error": "", "timestamp": ts(15.7)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 2,
         "tool_use_id": "tu_003", "tool_name": "read_file",
         "tool_input": {"path": "backend/analyzer/src/main/java/com/gak/analyzer/service/OllamaAnalyzerService.java",
                        "line_start": 385, "line_end": 420},
         "tool_result": "private String extractJsonText(String text) {\n    if (text == null) return \"{}\";\n    text = text.trim();\n    if (text.startsWith(\"```\")) {\n        text = text.replaceAll(\"^```(?:json)?\\\\n?\", \"\").replaceAll(\"\\\\n?```$\", \"\");\n    }\n    return text;\n}",
         "result_truncated": False, "duration_seconds": 0.03, "error": "", "timestamp": ts(19.8)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 3,
         "tool_use_id": "tu_004", "tool_name": "grep_code",
         "tool_input": {"pattern": "validateScores|extractJsonText", "path": "backend/analyzer/src/test",
                        "file_pattern": "*.java"},
         "tool_result": "OllamaAnalyzerServiceTest.java:89: validateScores_shouldClampValues\nOllamaAnalyzerServiceTest.java:102: validateScores_shouldFillMissingKeys",
         "result_truncated": False, "duration_seconds": 0.11, "error": "", "timestamp": ts(24.1)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 4,
         "messages_snapshot": [{"role": "user", "content_preview": researcher_user[:150]}],
         "stop_reason": "end_turn", "input_tokens": 3891, "output_tokens": 681,
         "duration_seconds": 8.43, "timestamp": ts(32.5)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Researcher",
         "status": "success", "iterations": 4, "input_tokens": 7837, "output_tokens": 1280,
         "duration_seconds": 41.3, "final_output_preview": researcher_report[:200],
         "error": "", "timestamp": ts(42.3)},

        # Planner
        {"event": "agent_start", "run_id": run_id, "agent_name": "Planner",
         "agent_role": "planner", "model": "claude-opus-4-7",
         "system_prompt": "당신은 시니어 소프트웨어 아키텍트입니다.",
         "user_message": f"## 이전 단계 결과\n\n{researcher_report[:300]}...\n\n---\n\n## 현재 작업\n\n{task}",
         "timestamp": ts(42.5)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Planner", "iteration": 0,
         "messages_snapshot": [{"role": "user", "content_preview": "Researcher 보고서 기반 구현 계획 수립"}],
         "stop_reason": "end_turn", "input_tokens": 4312, "output_tokens": 892,
         "duration_seconds": 11.87, "timestamp": ts(54.4)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Planner",
         "status": "success", "iterations": 0, "input_tokens": 4312, "output_tokens": 892,
         "duration_seconds": 12.1, "final_output_preview": planner_report[:200],
         "error": "", "timestamp": ts(54.6)},

        # Reviewer
        {"event": "agent_start", "run_id": run_id, "agent_name": "Reviewer",
         "agent_role": "reviewer", "model": "claude-opus-4-7",
         "system_prompt": "당신은 코드 리뷰 전문가입니다.",
         "user_message": f"계획서 검토 요청. 원본 작업:\n{task[:200]}",
         "timestamp": ts(55.0)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 0,
         "messages_snapshot": [{"role": "user", "content_preview": "계획서 검토"}],
         "stop_reason": "tool_use", "input_tokens": 5891, "output_tokens": 341,
         "duration_seconds": 4.82, "timestamp": ts(59.8)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 0,
         "tool_use_id": "tu_005", "tool_name": "read_file",
         "tool_input": {"path": "backend/analyzer/src/main/java/com/gak/analyzer/service/OllamaAnalyzerService.java",
                        "line_start": 238, "line_end": 270},
         "tool_result": "private Map<String, Double> validateScores(Map<String, Double> raw) { ... }",
         "result_truncated": False, "duration_seconds": 0.03, "error": "", "timestamp": ts(64.6)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 1,
         "tool_use_id": "tu_006", "tool_name": "grep_code",
         "tool_input": {"pattern": "fallbackAnalyzeBatch|createNeutralScores", "path": "backend/analyzer",
                        "file_pattern": "*.java"},
         "tool_result": "OllamaAnalyzerService.java:454: private Map<String,Double> createNeutralScores()\nOllamaAnalyzerService.java:471: public Mono<...> fallbackAnalyzeBatch(...)",
         "result_truncated": False, "duration_seconds": 0.09, "error": "", "timestamp": ts(68.7)},
        {"event": "api_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 2,
         "messages_snapshot": [{"role": "user", "content_preview": "계획서 검토"}],
         "stop_reason": "end_turn", "input_tokens": 7214, "output_tokens": 512,
         "duration_seconds": 7.31, "timestamp": ts(76.0)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Reviewer",
         "status": "success", "iterations": 2, "input_tokens": 13105, "output_tokens": 853,
         "duration_seconds": 23.4, "final_output_preview": reviewer_report[:200],
         "error": "", "timestamp": ts(78.4)},

        {"event": "workflow_end", "run_id": run_id, "status": "success",
         "duration_seconds": 79.1, "total_input_tokens": 25254, "total_output_tokens": 3025,
         "error": "", "timestamp": ts(79.1)},
    ]

    write_jsonl(run_dir / "events.jsonl", events)

    write_file(run_dir / "prompts" / "researcher_prompt.txt",
               f"=== SYSTEM PROMPT ===\n{researcher_system}\n\n=== USER MESSAGE ===\n{researcher_user}\n")
    write_file(run_dir / "prompts" / "planner_prompt.txt",
               f"=== SYSTEM PROMPT ===\n당신은 시니어 소프트웨어 아키텍트입니다.\n\n=== USER MESSAGE ===\n(Researcher 보고서 전달 후) {task}\n")
    write_file(run_dir / "prompts" / "reviewer_prompt.txt",
               f"=== SYSTEM PROMPT ===\n당신은 코드 리뷰 전문가입니다.\n\n=== USER MESSAGE ===\n아래 구현 계획을 검토하고 리스크를 평가해주세요.\n원본 작업:\n{task}\n")

    summary = f"""# 워크플로우 실행 요약

| 항목 | 값 |
|------|-----|
| Run ID | `{run_id}` |
| 시작 | 2026-05-21 14:30:22 |
| 소요 시간 | 79.1초 |
| 상태 | ✅ 성공 |
| 총 입력 토큰 | 25,254 |
| 총 출력 토큰 | 3,025 |
| API 호출 횟수 | 6 |
| 툴 호출 횟수 | 6 |
| 에러 횟수 | 0 |

## 작업

```
{task}
```

## 에이전트별 통계

| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |
|---------|------|------|----------|----------|---------|
| Researcher | ✅ success | 4 | 7,837 | 1,280 | 41.3s |
| Planner | ✅ success | 0 | 4,312 | 892 | 12.1s |
| Reviewer | ✅ success | 2 | 13,105 | 853 | 23.4s |

## 툴 호출 내역

| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |
|---------|-----|------------|---------|
| Researcher | `list_directory` | `{{"path": "backend/analyzer/.../service"}}` | 0.04s |
| Researcher | `read_file` | `{{"path": "...OllamaAnalyzerService.java", "line_start": 230}}` | 0.03s |
| Researcher | `read_file` | `{{"path": "...OllamaAnalyzerService.java", "line_start": 385}}` | 0.03s |
| Researcher | `grep_code` | `{{"pattern": "validateScores|extractJsonText"}}` | 0.11s |
| Reviewer | `read_file` | `{{"path": "...OllamaAnalyzerService.java", "line_start": 238}}` | 0.03s |
| Reviewer | `grep_code` | `{{"pattern": "fallbackAnalyzeBatch|createNeutralScores"}}` | 0.09s |

## 로그 파일

- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱)
- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트
"""
    write_file(run_dir / "summary.md", summary)
    print(f"✅ RUN 1 생성: {run_dir.name}")


# ─────────────────────────────────────────────
#  RUN 2 — RAG 파이프라인 HighlightEmbeddingService 추가
# ─────────────────────────────────────────────

def run_rag_pipeline() -> None:
    run_id = "d9e2f3a1"
    run_dir = LOG_ROOT / f"20260503_101544_{run_id}"

    task = (
        "과거 승인·거절된 하이라이트 사례를 pgvector로 검색해 "
        "OllamaAnalyzerService.analyzeHighlight() 호출 시 few-shot으로 주입하는 RAG 파이프라인을 추가하고 싶다. "
        "HighlightEmbeddingService와 HighlightRetrievalService 구현 범위를 파악해줘."
    )

    researcher_report = """\
# Researcher 보고서

## 1. 관련 파일 목록
- `backend/core-api/src/main/java/com/gak/core_api/rag/HighlightEmbeddingService.java`: 임베딩 생성 및 pgvector 저장 (신규)
- `backend/core-api/src/main/java/com/gak/core_api/rag/HighlightRetrievalService.java`: cosine 유사도 검색 (신규)
- `backend/analyzer/src/main/java/com/gak/analyzer/service/OllamaAnalyzerService.java`: `analyzeHighlight()` 122줄 — few-shot 주입 대상
- `backend/common/src/main/java/com/gak/common/dto/VodHighlightPoint.java`: 하이라이트 DTO
- `backend/core-api/src/main/resources/schema.sql`: pgvector 컬럼 추가 필요 여부 확인

## 2. 핵심 구조
- `HighlightEmbeddingService.embedAndStore(VodHighlight)`: 텍스트 임베딩 → pgvector INSERT (44줄)
- `HighlightEmbeddingService.requestEmbeddingPublic(String)`: Ollama nomic-embed-text 호출 (79줄)
- `OllamaAnalyzerService.fetchFewShotExamples(HighlightPromptPayload)`: core-api 호출로 유사 사례 조회 (131줄)
- `OllamaAnalyzerService.doAnalyzeHighlight()`: few-shot 문자열을 프롬프트에 주입 (156줄)

## 3. 의존 관계
- `VodHighlightAnalyzer` → (Kafka) → `core-api HighlightEmbeddingService`: 승인된 하이라이트 임베딩
- `OllamaAnalyzerService.analyzeHighlight` → HTTP → `core-api /internal/rag/highlight`: 유사 사례 조회
- pgvector: `vod_highlights` 테이블에 `embedding vector(768)` 컬럼 이미 존재 (확인 완료)

## 4. 변경 영향 범위
- `HighlightEmbeddingService`, `HighlightRetrievalService` 신규 추가 (core-api)
- `OllamaAnalyzerService.fetchFewShotExamples` 경로 변경 없음 — 구현만 채우면 됨
- Kafka consumer: 승인 이벤트 수신 후 임베딩 트리거 필요

## 5. 주의사항
- Ollama nomic-embed-text는 768차원 — pgvector 스키마의 vector(768)과 일치 확인 필요
- `embedAndStore` 실패가 메인 저장 경로에 영향을 주면 안 됨 — 비동기 격리 필수"""

    planner_report = """\
# Planner 구현 계획서

## 1. 목표 요약
하이라이트 판정 품질을 높이기 위해 과거 승인·거절 사례를 벡터 검색으로 찾아
LLM 프롬프트에 few-shot으로 주입하는 RAG 파이프라인을 구축한다.

## 2. 선택된 접근법
core-api에 `HighlightEmbeddingService` + `HighlightRetrievalService`를 추가하고,
analyzer의 `OllamaAnalyzerService.fetchFewShotExamples()`가 HTTP로 조회하는 구조를 채택.

## 3. 구현 단계

### 단계 1: HighlightEmbeddingService 구현
- `embedAndStore(VodHighlight)`: Ollama nomic-embed-text 호출 → `storeEmbedding()` — 비동기 격리
- 실패 시 메인 저장 경로에 영향 없도록 `onErrorResume(e -> Mono.empty())` 처리

### 단계 2: HighlightRetrievalService 구현
- pgvector cosine 유사도 쿼리: `ORDER BY embedding <=> $1 LIMIT $2`
- 결과를 few-shot 문자열로 직렬화해 반환

### 단계 3: OllamaAnalyzerService 연결
- `fetchFewShotExamples()`: core-api `/internal/rag/highlight` 엔드포인트 호출
- 결과를 `doAnalyzeHighlight()` 프롬프트에 삽입

## 4. 위험 요소
| 위험 | 가능성 | 완화 전략 |
|------|--------|----------|
| 임베딩 레이턴시 추가 | 보통 | 비동기 격리, 메인 경로 블로킹 없음 |
| pgvector 차원 불일치 | 낮음 | 스키마 확인 완료 (768차원) |"""

    reviewer_report = """\
# Reviewer 리뷰 보고서

## 최종 판정
[x] ✅ 승인 — 계획대로 진행 가능

## 리스크 분석
| 항목 | 리스크 | 설명 |
|------|--------|------|
| 임베딩 실패 전파 | 🟡 MEDIUM | onErrorResume 적용 확인 필요 |
| pgvector 인덱스 | 🟢 LOW | ivfflat 인덱스 존재 확인됨 |
| few-shot 길이 | 🟢 LOW | 최대 3개로 제한, 토큰 초과 없음 |

## 계획 검증 결과
### ✅ 정확한 부분
- `HighlightEmbeddingService` 44줄, `requestEmbeddingPublic` 79줄 위치 확인
- `fetchFewShotExamples` 131줄 — HTTP 호출 구조 확인

### ⚠️ 주의 필요
- `embedAndStore` 오류 격리가 실제로 `onErrorResume`으로 구현됐는지 코드에서 재확인 권장

## 결론
RAG 파이프라인은 기존 analyzer ↔ core-api 통신 패턴을 따르며 영향 범위가 명확. 승인."""

    events: list[dict] = [
        {"event": "workflow_start", "run_id": run_id, "task": task,
         "project_dir": "/Users/hoon/Desktop/Projects/project-neul", "timestamp": ts(-1555000)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Researcher",
         "agent_role": "researcher", "model": "claude-opus-4-7",
         "system_prompt": "당신은 코드베이스 탐색 전문가입니다.",
         "user_message": f"다음 작업과 관련된 코드베이스를 탐색하고 보고서를 작성해주세요:\n\n{task}",
         "timestamp": ts(-1554999)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 0,
         "tool_use_id": "tu_r1", "tool_name": "list_directory",
         "tool_input": {"path": "backend/core-api/src/main/java/com/gak/core_api/rag", "recursive": False},
         "tool_result": "HighlightEmbeddingService.java\nHighlightRetrievalService.java",
         "result_truncated": False, "duration_seconds": 0.04, "error": "", "timestamp": ts(-1554994)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 1,
         "tool_use_id": "tu_r2", "tool_name": "read_file",
         "tool_input": {"path": "backend/core-api/src/main/java/com/gak/core_api/rag/HighlightEmbeddingService.java"},
         "tool_result": "public class HighlightEmbeddingService {\n    public Mono<VodHighlight> embedAndStore(...)\n    public Mono<float[]> requestEmbeddingPublic(...)\n}",
         "result_truncated": False, "duration_seconds": 0.03, "error": "", "timestamp": ts(-1554988)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 2,
         "tool_use_id": "tu_r3", "tool_name": "grep_code",
         "tool_input": {"pattern": "fetchFewShotExamples|doAnalyzeHighlight", "path": "backend/analyzer", "file_pattern": "*.java"},
         "tool_result": "OllamaAnalyzerService.java:131: private Mono<String> fetchFewShotExamples\nOllamaAnalyzerService.java:156: private Mono<HighlightDecision> doAnalyzeHighlight",
         "result_truncated": False, "duration_seconds": 0.09, "error": "", "timestamp": ts(-1554982)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 3,
         "tool_use_id": "tu_r4", "tool_name": "grep_code",
         "tool_input": {"pattern": "vector\\(", "path": "backend", "file_pattern": "*.sql"},
         "tool_result": "schema.sql:47: embedding vector(768)",
         "result_truncated": False, "duration_seconds": 0.07, "error": "", "timestamp": ts(-1554976)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Researcher",
         "status": "success", "iterations": 5, "input_tokens": 8241, "output_tokens": 1104,
         "duration_seconds": 48.7, "final_output_preview": researcher_report[:200],
         "error": "", "timestamp": ts(-1554951)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Planner",
         "agent_role": "planner", "model": "claude-opus-4-7",
         "system_prompt": "당신은 시니어 소프트웨어 아키텍트입니다.",
         "user_message": "(Researcher 보고서 전달)", "timestamp": ts(-1554950)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Planner",
         "status": "success", "iterations": 0, "input_tokens": 5012, "output_tokens": 743,
         "duration_seconds": 13.2, "final_output_preview": planner_report[:200],
         "error": "", "timestamp": ts(-1554937)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Reviewer",
         "agent_role": "reviewer", "model": "claude-opus-4-7",
         "system_prompt": "당신은 코드 리뷰 전문가입니다.",
         "user_message": "(계획서 검토 요청)", "timestamp": ts(-1554936)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 0,
         "tool_use_id": "tu_rv1", "tool_name": "grep_code",
         "tool_input": {"pattern": "onErrorResume", "path": "backend/core-api/src/main/java/com/gak/core_api/rag", "file_pattern": "*.java"},
         "tool_result": "HighlightEmbeddingService.java:65: .onErrorResume(e -> { log.warn(...); return Mono.empty(); })",
         "result_truncated": False, "duration_seconds": 0.08, "error": "", "timestamp": ts(-1554930)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Reviewer",
         "status": "success", "iterations": 2, "input_tokens": 14302, "output_tokens": 618,
         "duration_seconds": 21.8, "final_output_preview": reviewer_report[:200],
         "error": "", "timestamp": ts(-1554914)},
        {"event": "workflow_end", "run_id": run_id, "status": "success",
         "duration_seconds": 85.2, "total_input_tokens": 27555, "total_output_tokens": 2465,
         "error": "", "timestamp": ts(-1554914)},
    ]

    write_jsonl(run_dir / "events.jsonl", events)
    write_file(run_dir / "prompts" / "researcher_prompt.txt",
               f"=== SYSTEM PROMPT ===\n당신은 코드베이스 탐색 전문가입니다.\n\n=== USER MESSAGE ===\n{task}\n")
    write_file(run_dir / "prompts" / "planner_prompt.txt",
               "=== SYSTEM PROMPT ===\n당신은 시니어 소프트웨어 아키텍트입니다.\n\n=== USER MESSAGE ===\n(Researcher 보고서 전달 후 구현 계획 수립)\n")
    write_file(run_dir / "prompts" / "reviewer_prompt.txt",
               "=== SYSTEM PROMPT ===\n당신은 코드 리뷰 전문가입니다.\n\n=== USER MESSAGE ===\n아래 구현 계획을 검토하고 리스크를 평가해주세요.\n")

    summary = f"""# 워크플로우 실행 요약

| 항목 | 값 |
|------|-----|
| Run ID | `{run_id}` |
| 시작 | 2026-05-03 10:15:44 |
| 소요 시간 | 85.2초 |
| 상태 | ✅ 성공 |
| 총 입력 토큰 | 27,555 |
| 총 출력 토큰 | 2,465 |
| API 호출 횟수 | 5 |
| 툴 호출 횟수 | 5 |
| 에러 횟수 | 0 |

## 작업

```
{task}
```

## 에이전트별 통계

| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |
|---------|------|------|----------|----------|---------|
| Researcher | ✅ success | 5 | 8,241 | 1,104 | 48.7s |
| Planner | ✅ success | 0 | 5,012 | 743 | 13.2s |
| Reviewer | ✅ success | 2 | 14,302 | 618 | 21.8s |

## 툴 호출 내역

| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |
|---------|-----|------------|---------|
| Researcher | `list_directory` | `{{"path": "backend/core-api/.../rag"}}` | 0.04s |
| Researcher | `read_file` | `{{"path": "...HighlightEmbeddingService.java"}}` | 0.03s |
| Researcher | `grep_code` | `{{"pattern": "fetchFewShotExamples|doAnalyzeHighlight"}}` | 0.09s |
| Researcher | `grep_code` | `{{"pattern": "vector(", "file_pattern": "*.sql"}}` | 0.07s |
| Reviewer | `grep_code` | `{{"pattern": "onErrorResume"}}` | 0.08s |

## 로그 파일

- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱)
- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트
"""
    write_file(run_dir / "summary.md", summary)
    print(f"✅ RUN 2 생성: {run_dir.name}")


# ─────────────────────────────────────────────
#  RUN 3 — AI 에이전트 워크플로우 구현 자체
# ─────────────────────────────────────────────

def run_agent_workflow_impl() -> None:
    run_id = "b5c8d2e4"
    run_dir = LOG_ROOT / f"20260526_113005_{run_id}"

    task = (
        "독립적으로 동작하는 에이전트 3개(Researcher, Planner, Reviewer)가 탐색·설계·검토를 "
        "각각 수행하는 AI 코딩 워크플로우를 Python으로 구현하고 Claude Code에 통합하고 싶다. "
        "각 에이전트는 별도의 Claude API 호출로 동작하며 컨텍스트를 공유하지 않아야 한다."
    )

    researcher_report = """\
# Researcher 보고서

## 1. 관련 파일 목록
- `scripts/` : 현재 mock_data JSON 파일만 존재, Python 스크립트 없음
- `CLAUDE.md` : Plan-First 규칙 + code-review-graph MCP 사용 지침 존재
- `.mcp.json` : code-review-graph MCP 서버 1개 등록됨
- `.claude/` : settings.json (PostToolUse 훅 존재), commands/ 디렉터리 없음

## 2. 핵심 구조
- `CLAUDE.md` 에 [Researcher] → [Planner] → [Implementer] → [Reviewer] 워크플로우 개념 이미 정의됨
- `.claude/settings.json` : `PostToolUse` 훅이 code-review-graph 업데이트 트리거 중
- `anthropic` Python SDK 미설치 상태 (pip 확인 필요)

## 3. 의존 관계
- 신규 `scripts/agent_workflow/` 패키지 → Claude API (anthropic SDK)
- `/workflow` 슬래시 커맨드 → `.claude/commands/workflow.md`
- MCP 서버 → `scripts/agent_workflow/mcp_server.py`

## 4. 변경 영향 범위
- 신규 파일: `scripts/agent_workflow/`, `scripts/run_workflow.py`, `.claude/commands/workflow.md`
- 기존 파일 수정: `CLAUDE.md` (워크플로우 규칙 추가), `.mcp.json` (서버 등록), `.gitignore`

## 5. 주의사항
- `.mcp.json` 수정 시 Claude Code 재시작 필요
- MCP 서버에서 ANTHROPIC_API_KEY 환경변수 필요"""

    planner_report = """\
# Planner 구현 계획서

## 1. 목표 요약
탐색·설계·검토를 각각 독립된 Claude API 호출로 수행하는 3-에이전트 워크플로우를 구축하고
Claude Code의 /workflow 슬래시 커맨드로 실행할 수 있게 통합한다.

## 2. 선택된 접근법
Python 패키지(`scripts/agent_workflow/`)를 신규 작성하고
Claude Code 통합은 slash command + MCP + CLAUDE.md 세 가지 방식을 병렬 적용.

## 3. 구현 단계

### 단계 1: 핵심 패키지
- `tools/filesystem.py`: read_file / list_directory / grep_code
- `agents/base.py`: tool_use 루프 + logger 주입
- `agents/researcher.py` / `planner.py` / `reviewer.py`
- `orchestrator.py`: 3개 에이전트 순차 실행

### 단계 2: Claude Code 통합
- `.claude/commands/workflow.md`: YAML frontmatter 포함
- `.mcp.json`: agent-workflow 서버 등록
- `CLAUDE.md`: 자동 실행 조건 추가

### 단계 3: 로깅
- `logging/events.py`, `logging/logger.py`: events.jsonl + prompts/ + summary.md

## 4. 위험 요소
| 위험 | 가능성 | 완화 전략 |
|------|--------|----------|
| API 키 환경변수 미설정 | 보통 | run_workflow.py에서 사전 검증 |
| Claude Code 슬래시 커맨드 인식 실패 | 낮음 | YAML frontmatter 필수 확인 |"""

    reviewer_report = """\
# Reviewer 리뷰 보고서

## 최종 판정
[x] ✅ 승인 — 계획대로 진행 가능

## 리스크 분석
| 항목 | 리스크 | 설명 |
|------|--------|------|
| ANTHROPIC_API_KEY 미설정 | 🟡 MEDIUM | run_workflow.py 진입점에서 사전 검증 |
| MCP 서버 재시작 필요 | 🟢 LOW | 사용자 안내로 해결 가능 |
| slash command frontmatter | 🟢 LOW | 이미 확인된 형식 |

## 결론
영향 범위가 신규 scripts/ 패키지와 설정 파일로 한정되며
기존 Java 백엔드·프론트엔드 코드에 영향 없음. 승인."""

    events: list[dict] = [
        {"event": "workflow_start", "run_id": run_id, "task": task,
         "project_dir": "/Users/hoon/Desktop/Projects/project-neul", "timestamp": ts(-36000)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Researcher",
         "agent_role": "researcher", "model": "claude-opus-4-7",
         "system_prompt": "당신은 코드베이스 탐색 전문가입니다.",
         "user_message": task, "timestamp": ts(-35999)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 0,
         "tool_use_id": "tu_a1", "tool_name": "list_directory",
         "tool_input": {"path": "scripts", "recursive": False},
         "tool_result": "mock_data.json\nmock_data_ascii.json\nmock_data_compact.json\nmock_data_final.json",
         "result_truncated": False, "duration_seconds": 0.03, "error": "", "timestamp": ts(-35994)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 1,
         "tool_use_id": "tu_a2", "tool_name": "read_file",
         "tool_input": {"path": "CLAUDE.md"},
         "tool_result": "## 개발 워크플로우 규칙\n### Plan-First 원칙\n...",
         "result_truncated": False, "duration_seconds": 0.02, "error": "", "timestamp": ts(-35988)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 2,
         "tool_use_id": "tu_a3", "tool_name": "read_file",
         "tool_input": {"path": ".mcp.json"},
         "tool_result": "{\"mcpServers\": {\"code-review-graph\": {...}}}",
         "result_truncated": False, "duration_seconds": 0.02, "error": "", "timestamp": ts(-35982)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Researcher", "iteration": 3,
         "tool_use_id": "tu_a4", "tool_name": "list_directory",
         "tool_input": {"path": ".claude", "recursive": False},
         "tool_result": "settings.json\nworktrees/",
         "result_truncated": False, "duration_seconds": 0.02, "error": "", "timestamp": ts(-35976)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Researcher",
         "status": "success", "iterations": 4, "input_tokens": 6104, "output_tokens": 987,
         "duration_seconds": 38.4, "final_output_preview": researcher_report[:200],
         "error": "", "timestamp": ts(-35961)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Planner",
         "agent_role": "planner", "model": "claude-opus-4-7",
         "system_prompt": "당신은 시니어 소프트웨어 아키텍트입니다.",
         "user_message": "(Researcher 보고서 전달)", "timestamp": ts(-35960)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Planner",
         "status": "success", "iterations": 0, "input_tokens": 4891, "output_tokens": 814,
         "duration_seconds": 14.3, "final_output_preview": planner_report[:200],
         "error": "", "timestamp": ts(-35946)},
        {"event": "agent_start", "run_id": run_id, "agent_name": "Reviewer",
         "agent_role": "reviewer", "model": "claude-opus-4-7",
         "system_prompt": "당신은 코드 리뷰 전문가입니다.",
         "user_message": "(계획서 검토 요청)", "timestamp": ts(-35945)},
        {"event": "tool_call", "run_id": run_id, "agent_name": "Reviewer", "iteration": 0,
         "tool_use_id": "tu_rv_a1", "tool_name": "read_file",
         "tool_input": {"path": ".claude/settings.json"},
         "tool_result": "{\"hooks\": {\"PostToolUse\": [{\"matcher\": \"Edit|Write|Bash\", ...}]}}",
         "result_truncated": False, "duration_seconds": 0.02, "error": "", "timestamp": ts(-35939)},
        {"event": "agent_end", "run_id": run_id, "agent_name": "Reviewer",
         "status": "success", "iterations": 1, "input_tokens": 12341, "output_tokens": 502,
         "duration_seconds": 19.7, "final_output_preview": reviewer_report[:200],
         "error": "", "timestamp": ts(-35925)},
        {"event": "workflow_end", "run_id": run_id, "status": "success",
         "duration_seconds": 75.0, "total_input_tokens": 23336, "total_output_tokens": 2303,
         "error": "", "timestamp": ts(-35925)},
    ]

    write_jsonl(run_dir / "events.jsonl", events)
    write_file(run_dir / "prompts" / "researcher_prompt.txt",
               f"=== SYSTEM PROMPT ===\n당신은 코드베이스 탐색 전문가입니다.\n\n=== USER MESSAGE ===\n{task}\n")
    write_file(run_dir / "prompts" / "planner_prompt.txt",
               "=== SYSTEM PROMPT ===\n당신은 시니어 소프트웨어 아키텍트입니다.\n\n=== USER MESSAGE ===\n(Researcher 보고서 전달 후 구현 계획 수립)\n")
    write_file(run_dir / "prompts" / "reviewer_prompt.txt",
               "=== SYSTEM PROMPT ===\n당신은 코드 리뷰 전문가입니다.\n\n=== USER MESSAGE ===\n아래 구현 계획을 검토하고 리스크를 평가해주세요.\n")

    summary = f"""# 워크플로우 실행 요약

| 항목 | 값 |
|------|-----|
| Run ID | `{run_id}` |
| 시작 | 2026-05-26 11:30:05 |
| 소요 시간 | 75.0초 |
| 상태 | ✅ 성공 |
| 총 입력 토큰 | 23,336 |
| 총 출력 토큰 | 2,303 |
| API 호출 횟수 | 5 |
| 툴 호출 횟수 | 5 |
| 에러 횟수 | 0 |

## 작업

```
{task}
```

## 에이전트별 통계

| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |
|---------|------|------|----------|----------|---------|
| Researcher | ✅ success | 4 | 6,104 | 987 | 38.4s |
| Planner | ✅ success | 0 | 4,891 | 814 | 14.3s |
| Reviewer | ✅ success | 1 | 12,341 | 502 | 19.7s |

## 툴 호출 내역

| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |
|---------|-----|------------|---------|
| Researcher | `list_directory` | `{{"path": "scripts"}}` | 0.03s |
| Researcher | `read_file` | `{{"path": "CLAUDE.md"}}` | 0.02s |
| Researcher | `read_file` | `{{"path": ".mcp.json"}}` | 0.02s |
| Researcher | `list_directory` | `{{"path": ".claude"}}` | 0.02s |
| Reviewer | `read_file` | `{{"path": ".claude/settings.json"}}` | 0.02s |

## 로그 파일

- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱)
- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트
"""
    write_file(run_dir / "summary.md", summary)
    print(f"✅ RUN 3 생성: {run_dir.name}")


# ─────────────────────────────────────────────
#  실행
# ─────────────────────────────────────────────

if __name__ == "__main__":
    run_llm_guardrail()
    run_rag_pipeline()
    run_agent_workflow_impl()
    print(f"\n모두 완료 → {LOG_ROOT}")
