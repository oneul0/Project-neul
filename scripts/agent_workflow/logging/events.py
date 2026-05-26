"""
이벤트 타입 정의 — 로거가 기록하는 모든 이벤트의 스키마.

각 이벤트는 JSON-serializable dataclass로 정의돼 있어
로그 파일에 그대로 직렬화된다.
"""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any
import time


def _now() -> float:
    return time.time()


# ─────────────────────────────────────────────
#  워크플로우 레벨
# ─────────────────────────────────────────────

@dataclass
class WorkflowStartEvent:
    event: str = "workflow_start"
    run_id: str = ""
    task: str = ""
    project_dir: str = ""
    timestamp: float = field(default_factory=_now)


@dataclass
class WorkflowEndEvent:
    event: str = "workflow_end"
    run_id: str = ""
    status: str = ""          # "success" | "error" | "interrupted"
    duration_seconds: float = 0.0
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    error: str = ""
    timestamp: float = field(default_factory=_now)


# ─────────────────────────────────────────────
#  에이전트 레벨
# ─────────────────────────────────────────────

@dataclass
class AgentStartEvent:
    event: str = "agent_start"
    run_id: str = ""
    agent_name: str = ""
    agent_role: str = ""
    model: str = ""
    system_prompt: str = ""
    user_message: str = ""   # task + context 합친 최초 메시지
    timestamp: float = field(default_factory=_now)


@dataclass
class AgentEndEvent:
    event: str = "agent_end"
    run_id: str = ""
    agent_name: str = ""
    status: str = ""          # "success" | "error" | "max_iterations"
    iterations: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    duration_seconds: float = 0.0
    final_output_preview: str = ""   # 앞 200자
    error: str = ""
    timestamp: float = field(default_factory=_now)


# ─────────────────────────────────────────────
#  API 호출 레벨
# ─────────────────────────────────────────────

@dataclass
class ApiCallEvent:
    """Claude API 단일 호출 기록."""
    event: str = "api_call"
    run_id: str = ""
    agent_name: str = ""
    iteration: int = 0
    messages_snapshot: list = field(default_factory=list)   # 전체 messages 히스토리
    stop_reason: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    duration_seconds: float = 0.0
    timestamp: float = field(default_factory=_now)


# ─────────────────────────────────────────────
#  툴 호출 레벨
# ─────────────────────────────────────────────

@dataclass
class ToolCallEvent:
    """단일 툴 호출 기록."""
    event: str = "tool_call"
    run_id: str = ""
    agent_name: str = ""
    iteration: int = 0
    tool_use_id: str = ""
    tool_name: str = ""
    tool_input: dict = field(default_factory=dict)
    tool_result: str = ""    # 앞 500자
    result_truncated: bool = False
    duration_seconds: float = 0.0
    error: str = ""
    timestamp: float = field(default_factory=_now)


# ─────────────────────────────────────────────
#  에러 레벨
# ─────────────────────────────────────────────

@dataclass
class ErrorEvent:
    event: str = "error"
    run_id: str = ""
    agent_name: str = ""
    iteration: int = 0
    error_type: str = ""
    error_message: str = ""
    timestamp: float = field(default_factory=_now)


# ─────────────────────────────────────────────
#  직렬화 헬퍼
# ─────────────────────────────────────────────

AnyEvent = (
    WorkflowStartEvent
    | WorkflowEndEvent
    | AgentStartEvent
    | AgentEndEvent
    | ApiCallEvent
    | ToolCallEvent
    | ErrorEvent
)


def event_to_dict(evt: Any) -> dict:
    """dataclass 이벤트를 JSON-serializable dict로 변환한다."""
    return asdict(evt)
