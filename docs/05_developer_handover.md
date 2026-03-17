# 👨‍💻 개발자 핸드오버 가이드 (Quick Reference)

> **최종 업데이트:** 2026-03-14

이 문서는 프로젝트의 유지보수 및 기능 확장을 위해 핵심 정보를 빠르게 찾아볼 수 있는 요약 가이드입니다.

---

## 🚀 서비스 실행 순서
1. **인프라**: `backend/`에서 `docker-compose up -d` (Postgres, Kafka, Redis, Ollama)
2. **분석 엔진**: Ollama 컨테이너 내에서 `ollama run llama3` (또는 설정된 모델) 실행 확인
3. **백엔드**: `collector` -> `analyzer` -> `core-api` 순으로 `./gradlew bootRun`
4. **프론트엔드**: `frontend/`에서 `npm run dev`

---

## 📂 핵심 설정 파일 위치
- **Kafka 토픽/그룹**: `backend/common/src/main/java/com/neul/common/config/`
- **LLM 프롬프트/모델**: `backend/analyzer/src/main/resources/application.yml`
- **DB 스키마**: `backend/core-api/src/main/resources/schema.sql`
- **프론트 API 엔드포인트**: `frontend/src/app/channels/[channelId]/page.tsx` (SSE 연결부)

---

## 🛠️ 주요 기능 확장 방법

### 1. 새로운 감정(Emotion) 추가하기
1. `OllamaAnalyzerService.java`의 `getSystemPrompt()` 수정:
   ```java
   "determine its overall emotion as one of: JOY, HOPE, ..., NEW_EMOTION"
   ```
2. 프론트엔드 `page.tsx`의 `EMOTION_MAP` 수정:
   ```javascript
   NEW_EMOTION: { color: "#...", label: "새감정", icon: "🔥" }
   ```

### 2. 하이라이트 감지 민감도 조절
- `ChatStreamService.java`:
  ```java
  if (!"NEUTRAL".equals(emotion) && score >= 0.8) // 임계값(0.8) 수정
  ```

### 3. 배치 크기 조절 (성능 튜닝)
- `NidChatCollector.java`:
  ```java
  .window(Duration.ofSeconds(2)) // 2초 주기 수정
  ```
- **분석 단위**: 각 모듈의 `application.yml` 내 `max-poll-records` (Kafka) 값 수정.

---

## 🔍 모니터링 및 로그
- **Collector**: `[NidChat]` 태그로 검색 (채팅 수집 여부 확인)
- **Analyzer**: `[Ollama]` 또는 `[Processor]` 태그로 검색 (AI 분석 결과 확인)
- **Core API**: `[Kafka]` 또는 `[Highlight]` 태그로 검색 (SSE 전송 및 하이라이트 확인)

---

## ⚠️ 주의사항
- **치지직 권한**: 비공개 방송이나 연령 제한 방송은 수집되지 않을 수 있습니다.
- **포트 충돌**: 8081(Collector), 8082(Analyzer), 8083(Core-API), 3000(Frontend) 포트가 사용 중인지 확인하세요.
- **Docker 볼륨**: DB 데이터가 꼬인 경우 `docker-compose down -v`로 볼륨을 밀고 다시 시작하는 것이 가장 빠릅니다.
