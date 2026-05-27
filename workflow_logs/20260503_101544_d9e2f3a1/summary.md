# 워크플로우 실행 요약

| 항목 | 값 |
|------|-----|
| Run ID | `d9e2f3a1` |
| 시작 | 2026-05-03 10:15:44 |
| 소요 시간 | 85.2초 |
| 상태 | ✅ 성공 |
| 총 입력 토큰 | 27,555 |
| 총 출력 토큰 | 2,465 |
| API 호출 횟수 | 5 |
| 툴 호출 횟수 | 5 |
| 에러 횟수 | 0 |

## 작업

```
과거 승인·거절된 하이라이트 사례를 pgvector로 검색해 OllamaAnalyzerService.analyzeHighlight() 호출 시 few-shot으로 주입하는 RAG 파이프라인을 추가하고 싶다. HighlightEmbeddingService와 HighlightRetrievalService 구현 범위를 파악해줘.
```

## 에이전트별 통계

| 에이전트 | 상태 | 반복 | 입력 토큰 | 출력 토큰 | 소요 시간 |
|---------|------|------|----------|----------|---------|
| Researcher | ✅ success | 5 | 8,241 | 1,104 | 48.7s |
| Planner | ✅ success | 0 | 5,012 | 743 | 13.2s |
| Reviewer | ✅ success | 2 | 14,302 | 618 | 21.8s |

## 툴 호출 내역

| 에이전트 | 툴 | 인자 (요약) | 소요 시간 |
|---------|-----|------------|---------|
| Researcher | `list_directory` | `{"path": "backend/core-api/.../rag"}` | 0.04s |
| Researcher | `read_file` | `{"path": "...HighlightEmbeddingService.java"}` | 0.03s |
| Researcher | `grep_code` | `{"pattern": "fetchFewShotExamples|doAnalyzeHighlight"}` | 0.09s |
| Researcher | `grep_code` | `{"pattern": "vector(", "file_pattern": "*.sql"}` | 0.07s |
| Reviewer | `grep_code` | `{"pattern": "onErrorResume"}` | 0.08s |

## 로그 파일

- `events.jsonl` — 전체 이벤트 스트림 (기계 파싱)
- `prompts/` — 각 에이전트에 전달된 정확한 프롬프트
