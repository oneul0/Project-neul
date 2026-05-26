"""
BaseAgent — 모든 에이전트의 기반 클래스.

tool_use 루프를 내장해 에이전트가 툴을 여러 번 호출한 뒤
최종 텍스트 응답을 반환하는 패턴을 처리한다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

import anthropic


@dataclass
class AgentConfig:
    """에이전트 설정."""

    name: str
    role: str                       # 'researcher' | 'planner' | 'reviewer'
    system_prompt: str
    model: str = "claude-opus-4-7"
    max_tokens: int = 8192
    thinking: dict[str, Any] | None = None
    tools: list[dict[str, Any]] = field(default_factory=list)
    max_tool_iterations: int = 20   # 무한 루프 방지


class BaseAgent:
    """
    단일 Claude API 호출 + tool_use 루프를 캡슐화하는 베이스 클래스.

    하위 클래스는 system_prompt 와 tools 를 정의하고,
    run(task, context) 를 호출하면 최종 텍스트 응답을 반환받는다.
    """

    def __init__(self, config: AgentConfig, base_dir: str = ".") -> None:
        self.config = config
        self.base_dir = base_dir
        self.client = anthropic.Anthropic()

    # ─────────────────────────────────────────
    #  Public API
    # ─────────────────────────────────────────

    def run(self, task: str, context: str = "") -> str:
        """
        에이전트를 실행하고 최종 텍스트 응답을 반환한다.

        Parameters
        ----------
        task:
            이번 단계에서 수행해야 할 작업 설명.
        context:
            이전 에이전트가 생성한 결과물 (없으면 빈 문자열).
        """
        user_message = self._build_user_message(task, context)
        messages: list[dict[str, Any]] = [{"role": "user", "content": user_message}]

        print(f"\n{'='*60}")
        print(f"  {self.config.name.upper()} 에이전트 실행 중...")
        print(f"{'='*60}")

        for iteration in range(self.config.max_tool_iterations):
            response = self._call_api(messages)
            assistant_content = response.content

            # 어시스턴트 메시지를 대화 히스토리에 추가
            messages.append({"role": "assistant", "content": assistant_content})

            # 종료 조건: 툴 사용 없이 텍스트로 응답
            if response.stop_reason == "end_turn":
                text = self._extract_text(assistant_content)
                print(f"  → {self.config.name} 완료 (툴 호출 {iteration}회)")
                return text

            # 툴 사용 요청 처리
            if response.stop_reason == "tool_use":
                tool_results = self._process_tool_calls(assistant_content)
                messages.append({"role": "user", "content": tool_results})
                continue

            # 예상치 못한 stop_reason
            print(f"  [WARN] 예상치 못한 stop_reason: {response.stop_reason}")
            break

        # 최대 반복 초과
        text = self._extract_text(messages[-1].get("content", []))
        print(f"  [WARN] {self.config.name}: 최대 반복({self.config.max_tool_iterations}) 도달")
        return text or "(응답 없음)"

    # ─────────────────────────────────────────
    #  Tool 실행 — 하위 클래스가 오버라이드 가능
    # ─────────────────────────────────────────

    def _execute_tool(self, tool_name: str, tool_input: dict[str, Any]) -> str:
        """툴을 실행하고 결과 문자열을 반환한다. 하위 클래스에서 오버라이드."""
        return f"[ERROR] 툴 '{tool_name}' 미지원 — 하위 클래스에서 구현하세요"

    # ─────────────────────────────────────────
    #  Private helpers
    # ─────────────────────────────────────────

    def _build_user_message(self, task: str, context: str) -> str:
        if context:
            return (
                f"## 이전 단계 결과\n\n{context}\n\n"
                f"---\n\n## 현재 작업\n\n{task}"
            )
        return task

    def _call_api(self, messages: list[dict[str, Any]]) -> Any:
        kwargs: dict[str, Any] = {
            "model": self.config.model,
            "max_tokens": self.config.max_tokens,
            "system": self.config.system_prompt,
            "messages": messages,
        }
        if self.config.tools:
            kwargs["tools"] = self.config.tools
        if self.config.thinking:
            kwargs["thinking"] = self.config.thinking

        return self.client.messages.create(**kwargs)

    def _process_tool_calls(self, content: list[Any]) -> list[dict[str, Any]]:
        """tool_use 블록들을 처리해 tool_result 목록을 반환한다."""
        tool_results: list[dict[str, Any]] = []

        for block in content:
            if block.type != "tool_use":
                continue

            print(f"  → 툴 호출: {block.name}({json.dumps(block.input, ensure_ascii=False)[:80]})")
            result = self._execute_tool(block.name, block.input)
            # 결과가 너무 길면 잘라냄
            if len(result) > 8000:
                result = result[:8000] + "\n… (이하 생략)"

            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": result,
            })

        return tool_results

    @staticmethod
    def _extract_text(content: Any) -> str:
        """content에서 text 블록들을 추출해 합친다."""
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts = [b.text for b in content if hasattr(b, "type") and b.type == "text"]
            return "\n".join(parts)
        return str(content)
