"""
Orchestrator — 3개 에이전트를 순서대로 실행하는 조율자.

흐름:
  1. Researcher → 코드베이스 탐색 → 보고서
  2. Planner    → 탐색 결과 기반 구현 계획 → 계획서
  3. Reviewer   → 계획서 검토 → 리뷰 보고서

각 에이전트는 독립적인 API 호출로 실행된다.
이전 에이전트의 출력은 다음 에이전트의 `context`로 전달된다.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .agents import PlannerAgent, ResearcherAgent, ReviewerAgent


@dataclass
class WorkflowResult:
    """워크플로우 전체 결과물."""

    task: str
    research_report: str
    implementation_plan: str
    review_report: str
    total_duration_seconds: float

    def save(self, output_dir: str = ".") -> str:
        """결과물을 파일로 저장하고 경로를 반환한다."""
        ts = time.strftime("%Y%m%d_%H%M%S")
        out_path = Path(output_dir) / f"workflow_{ts}.md"

        content = f"""# AI 에이전트 워크플로우 결과

**작업:** {self.task}
**소요 시간:** {self.total_duration_seconds:.1f}초
**생성 시각:** {time.strftime("%Y-%m-%d %H:%M:%S")}

---

{self.research_report}

---

{self.implementation_plan}

---

{self.review_report}
"""
        out_path.write_text(content, encoding="utf-8")
        return str(out_path)

    def summary(self) -> str:
        """터미널 출력용 요약."""
        sep = "─" * 60
        return f"""
{sep}
  워크플로우 완료  ({self.total_duration_seconds:.1f}초)
{sep}

## RESEARCHER 보고서
{self.research_report}

{sep}

## PLANNER 계획서
{self.implementation_plan}

{sep}

## REVIEWER 리뷰
{self.review_report}

{sep}
"""


class WorkflowOrchestrator:
    """
    3개 에이전트를 순서대로 실행하는 오케스트레이터.

    각 에이전트는 독립된 Claude API 인스턴스로 동작하며
    상태를 공유하지 않는다.
    """

    def __init__(self, base_dir: Optional[str] = None) -> None:
        self.base_dir = base_dir or os.getcwd()

    def run(self, task: str) -> WorkflowResult:
        """
        주어진 작업을 3개 에이전트로 처리한다.

        Parameters
        ----------
        task:
            수행할 작업 설명 (자연어로 작성).

        Returns
        -------
        WorkflowResult:
            세 에이전트의 출력이 담긴 결과 객체.
        """
        start = time.time()

        print(f"\n{'#'*60}")
        print(f"  AI 에이전트 워크플로우 시작")
        print(f"  작업: {task[:80]}{'...' if len(task) > 80 else ''}")
        print(f"  프로젝트: {self.base_dir}")
        print(f"{'#'*60}")

        # ── Step 1: Researcher ────────────────────────────────────
        researcher = ResearcherAgent(base_dir=self.base_dir)
        research_report = researcher.run(
            task=f"다음 작업과 관련된 코드베이스를 탐색하고 보고서를 작성해주세요:\n\n{task}",
        )

        # ── Step 2: Planner ───────────────────────────────────────
        planner = PlannerAgent(base_dir=self.base_dir)
        implementation_plan = planner.run(
            task=f"아래 작업에 대한 구현 계획을 작성해주세요:\n\n{task}",
            context=research_report,
        )

        # ── Step 3: Reviewer ──────────────────────────────────────
        reviewer = ReviewerAgent(base_dir=self.base_dir)
        review_report = reviewer.run(
            task=f"아래 구현 계획을 검토하고 리스크를 평가해주세요.\n\n원본 작업:\n{task}",
            context=f"## Researcher 보고서\n\n{research_report}\n\n## Planner 계획서\n\n{implementation_plan}",
        )

        total = time.time() - start

        return WorkflowResult(
            task=task,
            research_report=research_report,
            implementation_plan=implementation_plan,
            review_report=review_report,
            total_duration_seconds=total,
        )
