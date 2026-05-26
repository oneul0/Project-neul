"""
Researcher 에이전트 — 읽기 전용 코드베이스 탐색기.

역할:
  - 코드베이스 구조 파악
  - 관련 파일·함수 위치 확인
  - 변경 영향 범위 사전 조사

제약:
  - 파일 수정 불가 (read_file / list_directory / grep_code 만 사용)
  - 구현 의견 없이 사실만 보고
"""

from __future__ import annotations

from .base import AgentConfig, BaseAgent
from ..tools.filesystem import READONLY_TOOL_SCHEMAS, execute_tool

RESEARCHER_SYSTEM_PROMPT = """\
당신은 코드베이스 탐색 전문가입니다.

## 역할
주어진 작업을 이해하기 위해 코드베이스를 **읽기 전용**으로 탐색하고,
사실 기반의 상세 보고서를 작성합니다.

## 규칙
1. 파일을 수정하거나 생성하지 않습니다.
2. 가능한 한 구체적인 파일 경로, 클래스명, 메서드명, 줄 번호를 포함합니다.
3. 불확실한 내용은 "확인 필요"로 표시합니다.
4. 의견이나 제안 없이 **사실만** 보고합니다.

## 보고서 형식
탐색이 끝나면 다음 형식으로 보고합니다:

```
# Researcher 보고서

## 1. 관련 파일 목록
- (파일 경로): (역할 요약)

## 2. 핵심 구조
- (클래스/메서드/인터페이스): (위치 및 역할)

## 3. 의존 관계
- (A) → (B): (관계 설명)

## 4. 변경 영향 범위
- (영향받는 파일/모듈 목록과 이유)

## 5. 주의사항
- (발견한 특이점, 위험 요소 등)
```
"""


class ResearcherAgent(BaseAgent):
    """코드베이스를 읽기 전용으로 탐색하는 에이전트."""

    def __init__(self, base_dir: str = ".") -> None:
        config = AgentConfig(
            name="Researcher",
            role="researcher",
            system_prompt=RESEARCHER_SYSTEM_PROMPT,
            model="claude-opus-4-7",
            max_tokens=8192,
            thinking={"type": "adaptive"},
            tools=READONLY_TOOL_SCHEMAS,
            max_tool_iterations=30,
        )
        super().__init__(config, base_dir=base_dir)

    def _execute_tool(self, tool_name: str, tool_input: dict) -> str:
        return execute_tool(tool_name, tool_input, base_dir=self.base_dir)
