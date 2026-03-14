# 🛠️ 트러블슈팅 및 심화 구현 가이드

이 문서는 프로젝트 개발 과정에서 발생한 주요 문제들과 그 해결책, 그리고 핵심 로직의 심화 구현 내용을 체계적으로 정리합니다. 나중에 유사한 문제가 발생하거나 기능을 확장할 때 참고할 수 있습니다.

---

## 1. LLM 응답 역직렬화 (JSON Parsing) 이슈

### 🚨 문제 상황
Ollama와 같은 로컬 LLM은 프롬프트의 지시사항을 완벽히 따르지 않을 때가 있습니다. 특히 "JSON 배열로만 응답하라"고 해도 기분에 따라 단일 JSON 객체(`{...}`)를 보내거나, 텍스트 설명을 섞어 보낼 때가 있어 `ObjectMapper`가 에러를 냅니다.

### ✅ 해결책: 유연한 파싱 로직 (`OllamaAnalyzerService.java`)

```java
// JSON 배열([]) 또는 객체({}) 모두 대응 가능하도록 개선
String jsonStr = extractJsonArray(content);
List<Map<String, Object>> parsedList;

if (jsonStr.startsWith("{")) {
    Map<String, Object> singleObject = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
    parsedList = List.of(singleObject);
} else {
    parsedList = objectMapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
}
```

---

## 2. Kafka Type Id 헤더 충돌

### 🚨 문제 상황
Spring Kafka의 `JsonSerializer`는 기본적으로 메시지 헤더에 송신자 측의 클래스 패스(`__TypeId__`)를 포함합니다. 수신자 측(`core-api`)에 동일한 패키지의 클래스가 없으면 `ClassNotFoundException`이 발생하며 메시지 소비가 중단됩니다.

### ✅ 해결책: 타입 정보 비활성화 및 기본 타입 지정

**Producer (Analyzer):**
```java
// KafkaConfig.java
props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false); // __TypeId__ 제거
```

**Consumer (Core-API):**
```java
// KafkaConsumerConfig.java
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AnalyzedChatMessage.class.getName());
props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
```

---

## 3. R2DBC 엔티티와 DB 스키마 불일치

### 🚨 문제 상황
`AnalyzedChat.java` 엔티티에 새 컬럼(`emotion_type` 등)을 추가했는데, 기존 DB 테이블(`analyzed_chats`)에 해당 컬럼이 없으면 저장 시 SQL 에러가 발생합니다. Spring Data R2DBC는 스키마 자동 변경(ddl-auto) 기능이 없거나 제한적입니다.

### ✅ 해결책: 수동 스키마 관리 및 초기화

**schema.sql:**
```sql
CREATE TABLE IF NOT EXISTS analyzed_chats (
    id SERIAL PRIMARY KEY,
    emotion_type VARCHAR(50),  -- 추가된 컬럼
    emotion_score DOUBLE PRECISION,
    ...
);
```

---

## 4. 실시간 하이라이트 감지 (Spike Detection)

### ⚙️ 구현 메커니즘 (`ChatStreamService.java`)

```java
// 감정 스파이크 감지 및 쿨다운 적용
if (!"NEUTRAL".equals(emotion) && score >= 0.8) {
    HighlightRecord last = lastHighlights.get(roomId);
    if (last == null || last.getTimestamp().isBefore(LocalDateTime.now().minusSeconds(10))) {
        // 하이라이트 이벤트 발행 및 Chzzk 썸네일 URL 캡처
        ...
    }
}
```

---

## 5. 고속 배칭(Batching) 튜닝

### 📊 성능 최적화 전략 (`NidChatCollector.java`)

```java
// 2초 단위 배칭 파이프라인
chatSink.asFlux()
    .window(Duration.ofSeconds(2))
    .flatMap(window -> window.collectList())
    .filter(list -> !list.isEmpty())
    .subscribe(batchList -> sendToKafka(batchList));
```

---

## 6. 클라이언트 사이드 스냅샷 저장

### 💾 설계 의도 (`page.tsx`)

```javascript
// 클라이언트에서 직접 다운로드 수행
const handleDownload = async (imageUrl, timestamp) => {
    const response = await fetch(imageUrl);
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `highlight_${timestamp}.jpg`;
    a.click();
};
```
