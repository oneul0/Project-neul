"""
WorkflowLogger — 에이전트 워크플로우의 모든 이벤트를 기록하는 로거.

기록 대상:
  - 워크플로우 시작/종료
  - 각 에이전트의 시스템 프롬프트 + 유저 메시지 (정확한 프롬프트)
  - 매 API 호출의 전체 messages 히스토리 + 응답
  - 툴 호출: 이름, 인자, 결과
  - 소요 시간, 토큰 수, 에러

출력:
  workflow_logs/
  └── YYYYMMDD_HHMMSS_<run_id>/
      ├── events.jsonl       # 이벤트 스트림 (기계 파싱용)
      ├── prompts/           # 에이전트별 정확한 프롬프트 텍스트
      │   ├── researcher_iter_1.txt
      │   ├── planner_iter_1.txt
      │   └── reviewer_iter_1.txt
      └── summary.md         # 사람이 읽는 실행 요약
"""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

from .events import (
    WorkflowStartEvent,
    WorkflowEndEvent,
    AgentStartEvent,
    AgentEndEvent,
    ApiCallEvent,
    ToolCallEvent,
    ErrorEvent,
    event_to_dict,
)


class WorkflowLogger:
    """
    워크플로우 실행 전체를 파일에 기록하는 로거.

    사용 예시:
        logger = WorkflowLogger(log_dir="./workflow_logs")
        logger.on_workflow_start(run_id="...", task="...", project_dir="...")
        ...
        logger.on_workflow_end(run_id="...", status="success", ...)
    """

    def __init__(self, log_dir: str = "./workflow_logs") -> None:
        self.log_dir = Path(log_dir)
        self._run_dir: Path | None = None
        self._jsonl_path: Path | None = None
        self._run_id: str = ""
        self._start_time: float = 0.0
        # 토큰 누적 카운터
        self._total_input_tokens: int = 0
        self._total_output_tokens: int = 0

    # ─────────────────────────────────────────
    #  워크플로우 이벤트
    # ─────────────────────────────────────────

    def on_workflow_start(self, run_id: str, task: str, project_dir: str) -> None:
        self._run_id = run_id
        self._start_time = time.time()
        self._total_input_tokens = 0
        self._total_output_tokens = 0

        # 로그 디렉터리 생성
        ts = time.strftime("%Y%m%d_%H%M%S")
        short_id = run_id[:8]
        self._run_dir = self.log_dir / f"{ts}_{short_id}"
        self._run_dir.mkdir(parents=True, exist_ok=True)
        (self._run_dir / "prompts").mkdir(exist_ok=True)

        self._jsonl_path = self._run_dir / "events.jsonl"

        self._write(WorkflowStartEvent(
            run_id=run_id,
            task=task,
            project_dir=project_dir,
        ))
        print(f"  [LOG] 로그 저장 위치: {self._run_dir}")

    def on_workflow_end(
        self,
        run_id: str,
        status: str,
        duration_seconds: float,
        error: str = "",
    ) -> None:
        evt = WorkflowEndEvent(
            run_id=run_id,
            status=status,
            duration_seconds=duration_seconds,
            total_input_tokens=self._total_input_tokens,
            total_output_tokens=self._total_output_tokens,
            error=error,
        )
        self._write(evt)
        self._write_summary(status, duration_seconds, error)

    # ─────────────────────────────────────────
    #  에이전트 이벤트
    # ─────────────────────────────────────────

    def on_agent_start(
        self,
        agent_name: str,
        agent_role: str,
        model: str,
        system_prompt: str,
        user_message: str,
    ) -> None:
        self._write(AgentStartEvent(
            run_id=self._run_id,
            agent_name=agent_name,
            agent_role=agent_role,
            model=model,
            system_prompt=system_prompt,
            user_message=user_message,
        ))
        # 프롬프트 텍스트 파일로 저장 (재현용)
        self._save_prompt_file(agent_name, system_prompt, user_message)

    def on_agent_end(
        self,
        agent_name: str,
        status: str,
        iterations: int,
        input_tokens: int,
        output_tokens: int,
        duration_seconds: float,
        final_output: str = "",
        error: str = "",
    ) -> None:
        self._total_input_tokens += input_tokens
        self._total_output_tokens += output_tokens
        self._write(AgentEndEvent(
            run_id=self._run_id,
            agent_name=agent_name,
            status=status,
            iterations=iterations,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            duration_seconds=duration_seconds,
            final_output_preview=final_output[:200],
            error=error,
        ))

    # ─────────────────────────────────────────
    #  API 호출 이벤트
    # ─────────────────────────────────────────

    def on_api_call(
        self,
        agent_name: str,
        iteration: int,
        messages_snapshot: list,
        stop_reason: str,
        input_tokens: int,
        output_tokens: int,
        duration_seconds: float,
    ) -> None:
        # messages_snapshot은 크기가 크므로 각 메시지를 요약해서 저장
        snapshot_summary = self._summarize_messages(messages_snapshot)
        self._write(ApiCallEvent(
            run_id=self._run_id,
            agent_name=agent_name,
            iteration=iteration,
            messages_snapshot=snapshot_summary,
            stop_reason=stop_reason,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            duration_seconds=duration_seconds,
        ))

    # ─────────────────────────────────────────
    #  툴 호출 이벤트
    # ─────────────────────────────────────────

    def on_tool_call(
        self,
        agent_name: str,
        iteration: int,
        tool_use_id: str,
        tool_name: str,
        tool_input: dict,
        tool_result: str,
        duration_seconds: float,
        error: str = "",
    ) -> None:
        truncated = len(tool_result) > 500
        self._write(ToolCallEvent(
            run_id=self._run_id,
            agent_name=agent_name,
            iteration=iteration,
            tool_use_id=tool_use_id,
            tool_name=tool_name,
            tool_input=tool_input,
            tool_result=tool_result[:500],
            result_truncated=truncated,
            duration_seconds=duration_seconds,
            error=error,
        ))

    # ─────────────────────────────────────────
    #  에러 이벤트
    # ─────────────────────────────────────────

    def on_error(
        self,
        agent_name: str,
        iteration: int,
        error_type: str,
        error_message: str,
    ) -> None:
        self._write(ErrorEvent(
            run_id=self._run_id,
            agent_name=agent_name,
            iteration=iteration,
            error_type=error_type,
            error_message=error_message,
        ))

    # ─────────────────────────────────────────
    #  프로퍼티
    # ─────────────────────────────────────────

    @property
    def run_dir(self) -> Path | None:
        return self._run_dir

    @property
    def run_id(self) -> str:
        return self._run_id

    # ─────────────────────────────────────────
    #  Private helpers
    # ─────────────────────────────────────────

    def _write(self, evt: Any) -> None:
        """이벤트를 JSONL 파일에 한 줄로 기록한다."""
        if self._jsonl_path is None:
            return
        try:
            line = json.dumps(event_to_dict(evt), ensure_ascii=False)
            with self._jsonl_path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception as e:
            print(f"  [LOG ERROR] 이벤트 기록 실패: {e}")

    def _save_prompt_file(self, agent_name: str, system_prompt: str, user_message: str) -> None:
        """에이전트에 전달된 정확한 프롬프트를 텍스트 파일로 저장한다."""
        if self._run_dir is None:
            return
        fname = f"{agent_name.lower()}_prompt.txt"
        path = self._run_dir / "prompts" / fname
        content = (
            f"=== SYSTEM PROMPT ===\n{system_prompt}\n\n"
            f"=== USER MESSAGE ===\n{user_message}\n"
        )
        path.write_text(content, encoding="utf-8")

    def _summarize_messages(self, messages: list) -> list[dict]:
        """messages 히스토리를 요약된 형태로 변환한다 (토큰 절약)."""
        summary = []
        for msg in messages:
            role = msg.get("role", "?")
            content = msg.get("content", "")
            if isinstance(content, str):
                summary.append({"role": role, "content_preview": content[:200]})
            elif isinstance(content, list):
                parts = []
                for block in content:
                    if isinstance(block, dict):
                        btype = block.get("type", "?")
                        if btype == "text":
                            parts.append(f"[text: {block.get('text','')[:100]}]")
                        elif btype == "tool_use":
                            parts.append(f"[tool_use: {block.get('name','')}]")
                        elif btype == "tool_result":
                            parts.append(f"[tool_result: {str(block.get('content',''))[:80]}]")
                        else:
                            parts.append(f"[{btype}]")
                    else:
                        # content block object (not dict)
                        btype = getattr(block, "type", "?")
                        if btype == "text":
                            parts.append(f"[text: {getattr(block,'text','')[:100]}]")
                        elif btype == "tool_use":
                            parts.append(f"[tool_use: {getattr(block,'name','')}]")
                        else:
                            parts.append(f"[{btype}]")
                summary.append({"role": role, "content_blocks": parts})
        return summary

    def _write_summary(self, status: str, duration: float, error: str) -> None:
        """사람이 읽을 수 있는 마크다운 요약을 작성한다."""
        if self._run_dir is None:
            return

        # events.jsonl 파싱해서 통계 수집
        events = []
        if self._jsonl_path and self._jsonl_path.exists():
            for line in self._jsonl_path.read_text(encoding="utf-8").splitlines():
                try:
                    events.append(json.loads(line))
                except Exception:
                    pass

        workflow_start = next((e for e in events if e.get("event") == "workflow_start"), {})
        agent_ends = [e for e in events if e.get("event") == "agent_end"]
        tool_calls = [e for e in events if e.get("event") == "tool_call"]
        api_calls = [e for e in events if e.get("event") == "api_call"]
        errors = [e for e in events if e.get("event") == "error"]

        ts = time.strftime("%Y-%m-%d %H:%M:%S")
        lines = [
            f"# 워크플로우 실행 요약",
            f"",
            f"| 항목 | 값 |",
            f"|------|-----|",
            f"| Run ID | `{self._run_id}` |",
            f"| 시작 | {ts} |",
            f"| 소요 시간 | {duration:.1f}초 |",
            f"| 상태 | {'✅ 성공' if status == 'success' else '❌ ' + status} |",
            f"| 총 입력 토큰 | {self._total_input_tokens:,} |",
            f"| 총 출력 토큰 | {self._total_output_tokens:,} |",
            f"| API 호출 횟수 | {len(api_calls)} |",
            f"| 툴 호출 횟수 | {len(tool_calls)} |",
            f"| 에러 횟수 | {len(errors)} |",
            f"",
            f"## 작업",
            f"",
            f"```",
            workflow_start.get("task", "(없음)"),
            f"```",
            f"",
            f"## 에이전트별 통계",
            f"",
            f"| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |",
            f"|---------|------|------|----------|----------|---------|",
        ]

        for ae in agent_ends:
            status_icon = "✅" if ae.get("status") == "success" else "⚠️"
            lines.append(
                f"| {ae.get('agent_name','')} "
                f"| {status_icon} {ae.get('status','')} "
                f"| {ae.get('iterations',0)} "
                f"| {ae.get('input_tokens',0):,} "
                f"| {ae.get('output_tokens',0):,} "
                f"| {ae.get('duration_seconds',0):.1f}s |"
            )

        if tool_calls:
            lines += [
                f"",
                f"## 툴 호출 내역",
                f"",
                f"| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |",
                f"|---------|-----|------------|---------|",
            ]
            for tc in tool_calls:
                input_preview = json.dumps(tc.get("tool_input", {}), ensure_ascii=False)[:60]
                lines.append(
                    f"| {tc.get('agent_name','')} "
                    f"| `{tc.get('tool_name','')}` "
                    f"| `{input_preview}` "
                    f"| {tc.get('duration_seconds',0):.2f}s |"
                )

        if errors:
            lines += [
                f"",
                f"## 에러 내역",
                f"",
            ]
            for err in errors:
                lines.append(f"- **{err.get('error_type','')}** ({err.get('agent_name','')}, iter {err.get('iteration',0)}): {err.get('error_message','')}")

        if error:
            lines += [f"", f"## 치명적 에러", f"", f"```", error, f"```"]

        lines += [
            f"",
            f"## 로그 파일",
            f"",
            f"- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱용)",
            f"- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트",
        ]

        summary_path = self._run_dir / "summary.md"
        summary_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"  [LOG] 요약 저장: {summary_path}")


class NullLogger(WorkflowLogger):
    """로깅을 비활성화할 때 사용하는 no-op 로거."""

    def on_workflow_start(self, **kwargs) -> None: pass
    def on_workflow_end(self, **kwargs) -> None: pass
    def on_agent_start(self, **kwargs) -> None: pass
    def on_agent_end(self, **kwargs) -> None: pass
    def on_api_call(self, **kwargs) -> None: pass
    def on_tool_call(self, **kwargs) -> None: pass
    def on_error(self, **kwargs) -> None: pass
    def _write(self, evt: Any) -> None: pass
    def _save_prompt_file(self, *args, **kwargs) -> None: pass
    def _write_summary(self, *args, **kwargs) -> None: pass
