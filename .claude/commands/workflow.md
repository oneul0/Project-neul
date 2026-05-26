Run the 3-agent AI workflow (Researcher → Planner → Reviewer) for the given task.

## What this does
1. **Researcher**: Explores the codebase with read-only tools and produces a findings report
2. **Planner**: Creates a step-by-step implementation plan based on the research
3. **Reviewer**: Re-reads the code, checks the plan against reality, and gives a risk-scored verdict (✅/⚠️/❌)

## Usage
```
/workflow <task description>
```

## Steps to execute
1. Run the workflow script:
```bash
cd $CLAUDE_PROJECT_ROOT && python scripts/run_workflow.py --save "$ARGUMENTS"
```
2. Read the output carefully — especially the Reviewer's verdict
3. If the Reviewer gives ✅ or ⚠️ (with conditions), proceed with implementation following the Planner's steps
4. If ❌, surface the Reviewer's concerns to the user before proceeding

## Notes
- Requires `ANTHROPIC_API_KEY` environment variable
- Results are also saved to `./workflow_output/` for reference
- Typical runtime: 2–5 minutes depending on codebase size
