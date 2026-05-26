## AI 개발 역할 분리

이 프로젝트는 세 가지 역할을 분리해 AI 코딩 워크플로우를 운영한다.
각 역할은 독립적으로 동작하며, 이전 단계의 결과물을 입력으로 받는다.

### Researcher — 탐색 전용 (읽기 전용)

- **담당**: 코드베이스 구조 파악, 관련 파일·함수 위치 확인, 변경 영향 범위 사전 조사
- **도구**: Explore agent (읽기 전용), `semantic_search_nodes`, `query_graph`, `get_impact_radius`
- **규칙**: 이 단계에서 파일을 수정하지 않는다

### Planner — 설계 및 승인 게이트

- **담당**: 구현 계획 수립, 대안 비교(`docs/design/` 문서화), 영향 범위 명세
- **도구**: Plan mode (`EnterPlanMode` → `ExitPlanMode`), `get_affected_flows`
- **규칙**: 사용자 승인 전까지 구현하지 않는다. 주요 변경은 `docs/design/` 에 번호 붙은 설계 문서로 남긴다

### Reviewer — 검토 및 검증

- **담당**: 구현 후 변경 리스크 검토, 영향받은 실행 흐름 확인, 테스트 커버리지 점검
- **도구**: code-review skill, `detect_changes`, `get_review_context`, `query_graph` pattern="tests_for"
- **규칙**: 구현 완료 후 반드시 `detect_changes` 로 리스크 스코어 확인 후 머지

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
