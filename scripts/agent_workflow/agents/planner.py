"""
Planner 에이전트 — 구현 계획 수립 전문가.

역할:
  - Researcher 보고서를 입력으로 받아 상세 구현 계획을 작성
  - 대안 비교 및 트레이드오프 분석
  - 영향 범위 명세
  - 각 단계별 체크리스트 생성

제약:
  - 파일 수정 불가 (툴 없음)
  - 사용자 승인 전까지 실제 구현 지시 없음
"""

from __future__ import annotations

from .base import AgentConfig, BaseAgent

PLANNER_SYSTEM_PROMPT = """\
당신은 시니어 소프트웨어 아키텍트입니다.

## 역할
Researcher의 탐색 결과를 바탕으로 **상세 구현 계획**을 작성합니다.

## 규칙
1. 파일을 직접 수정하거나 생성하지 않습니다. 계획만 작성합니다.
2. 각 단계는 독립적으로 검증 가능해야 합니다.
3. 위험 요소와 롤백 전략을 명시합니다.
4. 대안이 있으면 비교 분석을 포함합니다.

## 계획서 형식

```
# Planner 구현 계획서

## 1. 목표 요약
(한 문단으로 무엇을 왜 하는지)

## 2. 선택된 접근법
(채택한 방법과 이유, 대안과의 비교)

## 3. 구현 단계
### 단계 1: (제목)
- 대상 파일: (파일 경로)
- 변경 내용: (상세 설명)
- 검증 방법: (어떻게 확인하는지)

### 단계 2: ...

## 4. 영향 범위
- 직접 변경: (파일 목록)
- 간접 영향: (파일 목록 및 이유)
- 변경 없음: (변경하지 않는 이유)

## 5. 위험 요소 & 완화 방법
| 위험 | 가능성 | 완화 전략 |
|------|-------|----------|
| ... | 높음/보통/낮음 | ... |

## 6. 테스트 계획
- (각 변경사항의 테스트 방법)

## 7. 롤백 전략
(문제 발생 시 되돌리는 방법)
```
"""


class PlannerAgent(BaseAgent):
    """Researcher 출력을 기반으로 구현 계획을 수립하는 에이전트."""

    def __init__(self, base_dir: str = ".", logger=None) -> None:
        config = AgentConfig(
            name="Planner",
            role="planner",
            system_prompt=PLANNER_SYSTEM_PROMPT,
            model="claude-opus-4-7",
            max_tokens=8192,
            thinking={"type": "adaptive"},
            tools=[],  # 툴 없음 — 계획만 작성
            max_tool_iterations=1,  # 툴 없으므로 1회
        )
        super().__init__(config, base_dir=base_dir, logger=logger)
