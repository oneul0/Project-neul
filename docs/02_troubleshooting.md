# Troubleshooting & Debugging Log

## [TS-001] Backend Port Conflict (8082)

- **날짜:** 2026-03-14
- **오류 증상:** `analyzer` 모듈 실행 시 `Port 8082 already in use` 에러 발생하며 서버 시작 실패.
- **에러 로그:** `Web server failed to start. Port 8082 was already in use.`
- **원인 분석:** 기존에 실행 중이던 프로세스가 정상 종료되지 않아 포트를 점유하고 있었음.
- **해결 방법:** `lsof -i :8082` (또는 Windows의 `netstat -ano | findstr :8082`)를 통해 PID 확인 후 `kill` 처리.
- **향후 예방책:** 개발 환경 종료 시 프로세스 종료 확인 습관화 및 Gradle `bootRun` 병렬 실행 시 포트 충돌 주의.

---

## [TS-002] DTO Consolidation 후 대규모 Import 에러

- **날짜:** 2026-03-14
- **오류 증상:** DTO를 `common` 모듈로 옮긴 후 모든 모듈에서 빌드 실패 및 IDE 에러(Class not found) 발생.
- **에러 로그:** `The import com.neul.analyzer.dto.RawChatMessage cannot be resolved` 외 100건 이상.
- **원인 분석:** 패키지 경로 변경으로 인한 기존 Import문 무효화 및 `build.gradle` 의존성 누락.
- **해결 방법:** 
  1. 각 모듈의 `build.gradle`에 `implementation project(':common')` 추가.
  2. 전수 Import문 업데이트 (`collector`, `analyzer`, `core-api`, 및 테스트 코드 포함).
  3. `DummyChatCollector` 등 관련 로직 리팩토링.
- **향후 예방책:** 리팩토링 전 수 영향도를 먼저 파악하고, 패키지 이동 시 IDE의 'Move' 기능을 활용하여 자동 업데이트 유도.
