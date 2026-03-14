# 🛠️ 늘(Neul) 프로젝트 개발 및 트러블슈팅 로그

이 문서는 프로젝트 진행 과정에서 발생한 트러블슈팅 사례와 주요 기능 구현 사항을 날짜별로 기록하여, 기술적 결정의 배경과 해결 방법을 추적할 수 있도록 합니다.

---

## [2026-03-14] 실시간 분석 고도화 및 하이라이트 기능

> **주요 성과:** 2초 단위 마이크로배칭, 7가지 감정 분석, 실시간 하이라이트 감지 및 스냅샷 다운로드.

### 1. LLM 응답 역직렬화 (JSON Parsing) 이슈
**문제:** Ollama가 가끔 지시를 무시하고 단일 JSON 객체(`{...}`)를 보내거나 텍스트를 섞어 응답함.
**해결:** `OllamaAnalyzerService.java`에서 응답의 시작 문자를 확인하여 분기 처리하는 유연한 파싱 로직 도입.
```java
String jsonStr = extractJsonArray(content);
if (jsonStr.startsWith("{")) {
    Map<String, Object> singleObject = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
    parsedList = List.of(singleObject);
} else {
    parsedList = objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
}
```

### 2. R2DBC 엔티티 및 DB 스키마 불일치
**문제:** 엔티티에 새 컬럼(`emotion_type`, `emotion_score`)을 추가했으나 기존 DB 테이블에 반영되지 않아 에러 발생.
**해결:** `schema.sql` 최신화 및 `docker-compose down -v`를 통한 볼륨 초기화 권장.
```sql
CREATE TABLE IF NOT EXISTS analyzed_chats (
    id SERIAL PRIMARY KEY,
    emotion_type VARCHAR(50), 
    emotion_score DOUBLE PRECISION,
    ...
);
```

### 3. 실시간 하이라이트 및 2초 단위 고속 배칭
**구현:** 60초 주기를 2초로 단축하여 실시간성을 확보하고, 0.8 이상의 감정 스파이크를 자동 감지함.
```java
// NidChatCollector.java (2s Batching)
.window(Duration.ofSeconds(2))
.flatMap(window -> window.collectList())

// ChatStreamService.java (Spike Detection)
if (!"NEUTRAL".equals(emotion) && score >= 0.8) { ... }
```

---

## [2026-03-05] 외부 라이브러리 연동 및 문서화 표준 정립

> **주요 성과:** Chzzk Socket.IO 연동 및 기술 문서(ADR, Progress Log) 체계 구축.

### 1. Socket.IO 의존성 임포트 에러
**문제:** `import io.socket` 구문을 찾지 못하는 빌드 에러 발생.
**해결:** `build.gradle`에 `implementation 'io.socket:socket.io-client:2.1.1'` 의존성을 추가하고 Gradle 리로드 수행.

---

## [2026-03-02] Kafka 직렬화 및 패키지 충돌 해결

> **주요 성과:** 마이크로서비스 간 데이터 전달 최적화 및 타임체크 로직 개선.

### 1. Kafka `__TypeId__` 헤더에 의한 역직렬화 에러
**문제:** 분석 서버에서 보낸 메시지 헤더의 클래스 패스가 API 서버의 패키지와 달라 수신 실패.
**해결:** `JsonSerializer.ADD_TYPE_INFO_HEADERS`를 `false`로 설정하고 수신측에서 기본 타입을 명시함.
```java
// Producer
props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

// Consumer
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AnalyzedChatMessage.class.getName());
```

---

## [2026-02-27] 프로젝트 초기 인프라 구축

> **주요 성과:** Docker 기반 Kafka, Redis, PostgreSQL 환경 조성 및 기본 SSE 스트림 구현.

### 1. DB 연결 (R2DBC) 타임아웃
**문제:** Docker 네트워크 환경에서 초기 DB 연결 시 간헐적 타임아웃 발생.
**해결:** Docker Compose의 `healthcheck`를 통해 DB 준비 후 애플리케이션 실행을 보장하고, 연결 문자열에 `connectTimeout` 옵션 추가.
