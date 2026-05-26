## 개발 워크플로우 규칙

### Plan-First 원칙

새 기능 구현이나 기존 코드 수정 전에 반드시 Plan Mode를 사용한다.

1. `EnterPlanMode` → 변경 범위·영향 파일·대안을 정리한 구현 계획 작성
2. 사용자 승인(`ExitPlanMode`) 전까지 파일을 수정하지 않는다
3. 관련 설계 문서(`docs/design/`)가 있으면 구현 전에 반드시 확인한다

> 목적: AI가 작업 범위를 벗어나거나 의도치 않은 파일을 수정하는 것을 방지하고,
> 사람이 변경 내용을 검토·승인한 뒤에만 구현이 진행되도록 한다.

### 구현 순서

```
[Researcher] 탐색 (읽기 전용)
      ↓
[Planner]   설계 문서 작성 + 사용자 승인 (EnterPlanMode → ExitPlanMode)
      ↓
[Implementer] 코드 구현
      ↓
[Reviewer]  변경 리스크 검토 (detect_changes + code-review skill)
```

---

<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
| ------ | ---------- |
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
