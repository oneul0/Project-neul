#!/usr/bin/env python3
"""
agent_workflow MCP 서버.

Claude Code에 `run_workflow` 툴을 노출해 3-에이전트 워크플로우를
Claude Code 안에서 직접 호출할 수 있게 한다.

실행:
    python -m agent_workflow.mcp_server

또는 .mcp.json 에 등록 후 Claude Code가 자동 시작.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

# MCP SDK
try:
    import mcp.server.stdio
    import mcp.types as types
    from mcp.server import Server
except ImportError:
    print(
        "[ERROR] mcp 패키지가 없습니다.\n"
        "  pip install mcp\n",
        file=sys.stderr,
    )
    sys.exit(1)

# agent_workflow 패키지 경로 설정
SCRIPTS_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from agent_workflow import WorkflowOrchestrator

# ─────────────────────────────────────────────
#  MCP 서버 정의
# ─────────────────────────────────────────────

app = Server("agent-workflow")

PROJECT_DIR = os.environ.get(
    "AGENT_WORKFLOW_PROJECT_DIR",
    str(Path(__file__).parent.parent.parent),  # 프로젝트 루트 기본값
)


@app.list_tools()
async def list_tools() -> list[types.Tool]:
    return [
        types.Tool(
            name="run_workflow",
            description=(
                "3개의 독립 에이전트(Researcher → Planner → Reviewer)로 "
                "코딩 작업을 분석한다.\n\n"
                "- Researcher: 코드베이스를 읽기 전용으로 탐색해 관련 파일·구조 보고\n"
                "- Planner: 탐색 결과 기반으로 단계별 구현 계획 수립\n"
                "- Reviewer: 계획서를 코드와 대조해 리스크 평가 및 ✅/⚠️/❌ 판정\n\n"
                "새 기능 추가, 여러 파일 수정, 영향 범위 불명확한 작업 전에 호출하세요."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task": {
                        "type": "string",
                        "description": "수행할 작업의 자연어 설명",
                    },
                    "project_dir": {
                        "type": "string",
                        "description": (
                            "프로젝트 루트 디렉터리 경로. "
                            "생략 시 환경 변수 AGENT_WORKFLOW_PROJECT_DIR 또는 "
                            "서버 기본값 사용."
                        ),
                    },
                },
                "required": ["task"],
            },
        )
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent]:
    if name != "run_workflow":
        raise ValueError(f"알 수 없는 툴: {name}")

    task = arguments.get("task", "").strip()
    if not task:
        return [types.TextContent(type="text", text="[ERROR] task가 비어 있습니다.")]

    project_dir = arguments.get("project_dir") or PROJECT_DIR

    if not os.environ.get("ANTHROPIC_API_KEY"):
        return [
            types.TextContent(
                type="text",
                text=(
                    "[ERROR] ANTHROPIC_API_KEY 환경 변수가 설정되지 않았습니다.\n"
                    "  export ANTHROPIC_API_KEY=sk-ant-..."
                ),
            )
        ]

    try:
        orchestrator = WorkflowOrchestrator(base_dir=project_dir)
        result = orchestrator.run(task)
        return [types.TextContent(type="text", text=result.summary())]
    except Exception as e:
        return [types.TextContent(type="text", text=f"[ERROR] 워크플로우 실행 실패: {e}")]


# ─────────────────────────────────────────────
#  진입점
# ─────────────────────────────────────────────

async def main() -> None:
    async with mcp.server.stdio.stdio_server() as (read_stream, write_stream):
        await app.run(
            read_stream,
            write_stream,
            app.create_initialization_options(),
        )


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
