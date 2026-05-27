# 워크플로우 실행 요약

| 항목 | 값 |
|------|-----|
| Run ID | `a3f7b1c2` |
| 시작 | 2026-05-21 14:30:22 |
| 소요 시간 | 79.1초 |
| 상태 | ✅ 성공 |
| 총 입력 토큰 | 25,254 |
| 총 출력 토큰 | 3,025 |
| API 호출 횟수 | 6 |
| 툴 호출 횟수 | 6 |
| 에러 횟수 | 0 |

## 작업

```
OllamaAnalyzerService.validateScores()가 합계 이탈·키 불일치 케이스를 처리하지 못해 오염된 감정 점수가 VodHighlightAnalyzer까지 흘러가는 문제를 수정하고 싶다. extractJsonText()와 validateScores()에 3계층 방어 로직을 추가해줘.
```

## 에이전트별 통계

| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |
|---------|------|------|----------|----------|---------|
| Researcher | ✅ success | 4 | 7,837 | 1,280 | 41.3s |
| Planner | ✅ success | 0 | 4,312 | 892 | 12.1s |
| Reviewer | ✅ success | 2 | 13,105 | 853 | 23.4s |

## 툴 호출 내역

| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |
|---------|-----|------------|---------|
| Researcher | `list_directory` | `{"path": "backend/analyzer/.../service"}` | 0.04s |
| Researcher | `read_file` | `{"path": "...OllamaAnalyzerService.java", "line_start": 230}` | 0.03s |
| Researcher | `read_file` | `{"path": "...OllamaAnalyzerService.java", "line_start": 385}` | 0.03s |
| Researcher | `grep_code` | `{"pattern": "validateScores|extractJsonText"}` | 0.11s |
| Reviewer | `read_file` | `{"path": "...OllamaAnalyzerService.java", "line_start": 238}` | 0.03s |
| Reviewer | `grep_code` | `{"pattern": "fallbackAnalyzeBatch|createNeutralScores"}` | 0.09s |

## 로그 파일

- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱)
- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트
