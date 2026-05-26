---
description: "3-에이전트 AI 워크플로우 실행 (Researcher → Planner → Reviewer). 새 기능 구현 또는 영향 범위가 불명확한 작업 전에 사용."
allowed-tools: ["Bash", "Read"]
---

Run the following command and present the results to the user:

```bash
cd $CLAUDE_PROJECT_ROOT && python scripts/run_workflow.py --save "$ARGUMENTS"
```

After running:
1. Show the full output (Researcher report → Planner plan → Reviewer verdict)
2. Highlight the Reviewer's final verdict (✅ / ⚠️ / ❌)
3. If ✅ or ⚠️: summarize what to implement and in which files
4. If ❌: explain why the plan needs revision before proceeding

Requirements:
- ANTHROPIC_API_KEY must be set in the environment
- Results are saved to ./workflow_output/ automatically
