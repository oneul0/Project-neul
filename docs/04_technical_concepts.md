# 각(Gak) 프로젝트 핵심 개념 학습

> 구현한 기능에서 사용된 기술 개념을 날짜별·포스팅 형식으로 정리합니다.

---

## [2026-02-27] 백엔드 마이크로서비스 파이프라인 구현

> **관련 모듈:** `collector`, `analyzer`, `core-api`  
> **핵심 스택:** Kafka, Spring Kafka, Reactor, R2DBC, Redis, SSE, Resilience4j

---

# 📌 1. Apache Kafka — 분산 메시지 브로커

## Kafka가 왜 필요한가?

유튜브/치지직 같은 라이브 방송에서 수천 명이 동시에 채팅을 보내면, 수집 서버가 직접 AI 분석 서버를 HTTP로 호출하면 두 서버가 **강하게 결합**됩니다. 분석이 느려지면 수집도 막히는 구조죠.

Kafka는 이 사이에 놓이는 **고속 우편함**입니다. 수집 서버는 메시지를 Kafka에 던지고 끝. 분석 서버는 자기 속도에 맞춰 꺼내서 처리합니다.

```
[collector]  →     Kafka      →  [analyzer]  →     Kafka      →  [core-api]
 (NidChat)     raw-chat-batch      (Ollama)    analyzed-chat        (SSE)
```

## 핵심 개념 3가지

### 1) Topic — 우편함의 주소
메시지가 저장되는 논리적 공간. 이 프로젝트에서는 2개:
- `raw-chat-topic` — 수집된 원본 채팅
- `analyzed-chat-topic` — 감정 분석이 완료된 채팅

### 2) Partition — 우편함을 여러 칸으로 나누기
토픽을 **물리적으로 여러 조각**으로 나눈 것. 3개 파티션이면 3개 브로커에 분산 저장 가능.

**핵심:** 같은 `key`를 가진 메시지는 항상 **같은 파티션**으로 갑니다.
이 프로젝트는 `roomId`를 key로 사용하기 때문에 같은 방의 채팅은 항상 같은 파티션 → **메시지 순서 보장**.

```java
// ChatProducer.java
kafkaTemplate.send("raw-chat-topic", message.getRoomId(), message);
//                   topic           ↑ key = roomId       value
```

### 3) Consumer Group — 역할별 소비자 그룹
- `neul-analyzer-group` — analyzer 전용
- `neul-core-api-group` — core-api 전용

같은 그룹 내에서는 각 파티션을 한 consumer가 독점. 그룹이 다르면 **같은 메시지를 각각 받음**.

---

# 📌 2. Spring Kafka — @KafkaListener 배치 처리

## 왜 배치(Batch)인가?

AI 분석 API는 메시지 1개씩 보내면 API 호출 횟수가 너무 많아집니다. 50개를 묶어서 한 번에 보내면 훨씬 효율적입니다. 이걸 **마이크로배칭**이라고 합니다.

## 설정 방법

```java
// KafkaConfig.java (analyzer)
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory() {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory());
    factory.setBatchListener(true);  // ← 배치 모드 ON
    return factory;
}
```

```java
// ConsumerConfig
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50); // 한 번에 최대 50개 폴링
```

## 사용 방법

```java
// ChatAnalysisProcessor.java
@KafkaListener(
    topics = "raw-chat-topic",
    groupId = "neul-analyzer-group",
    containerFactory = "batchKafkaListenerContainerFactory"  // ← 배치 컨테이너 지정
)
public void processBatch(List<String> rawMessages) {
    // rawMessages = 최대 50개의 메시지가 한 번에 들어옴
}
```

> **포인트:** `List<String>`으로 파라미터 타입을 선언하면 Spring Kafka가 자동으로 배치로 처리해줍니다.

---

# 📌 3. Project Reactor — Mono와 Flux

## Reactive Programming이란?

일반적인 Java 코드는 결과가 나올 때까지 **기다립니다(블로킹)**. 스레드가 그 시간 동안 낭비됩니다.

Reactive는 "결과가 나오면 알려줘" 방식입니다. 스레드는 기다리는 동안 다른 일을 합니다.

## Mono — 0 또는 1개의 결과

```java
// GeminiAnalyzerService.java
public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<CompressedChat> chats) {
    return Mono.fromCallable(() -> {
        // 실제 Gemini API 호출
        Thread.sleep(100);
        return chats.stream().map(this::simulateEmotion).toList();
    });
}
```

`Mono`는 **미래의 결과 하나**를 담은 컨테이너입니다. 아직 실행되지 않고, 누군가 `subscribe()`나 `block()`을 호출할 때 실행됩니다.

---

### Q. `List<AnalyzedChatMessage>`만 반환하면 되는데 왜 `Mono<List<...>>`로 감싸나요?

"나중에 실행될 작업"을 담은 상자를 반환해서, **호출한 쪽이 블로킹 없이 다른 일을 할 수 있기** 때문입니다.

```java
// ❌ 동기 방식 — 결과 나올 때까지 스레드가 여기서 멈춤
public List<AnalyzedChatMessage> analyzeBatch(...) {
    Thread.sleep(3000); // Gemini 응답 대기... 3초간 스레드 낭비
    return result;
}

// ✅ 비동기 방식 — "나중에 실행되는 작업 상자"만 즉시 반환
public Mono<List<AnalyzedChatMessage>> analyzeBatch(...) {
    return webClient.post()
        .bodyValue(buildPrompt(chats))
        .retrieve()
        .bodyToMono(GeminiResponse.class)
        .map(this::parseResult);
    // 이 메서드 자체는 즉시 반환 → 스레드 블로킹 없음
}
```

```
[일반 방식] 스레드 1개가 Gemini 3초 대기 → 그 3초 동안 아무것도 못 함

[Mono 방식] Gemini 요청 보내고 → 스레드가 다른 요청 처리
            Gemini 응답 오면 → 그때 결과 처리 재개
            = 스레드 1개로 수백 건 동시 처리 가능
```

이 프로젝트가 **Spring WebFlux** 기반인 이유가 여기 있습니다. Netty 이벤트 루프 위에서 동작하므로 스레드가 블로킹되면 전체 처리량이 급감합니다.

---

### Q. `Mono<List<...>>`가 어색합니다. Flux가 더 맞지 않나요?

`Flux`는 데이터가 **여러 번에 걸쳐 발행**될 때(스트림) 씁니다.

`Mono<List<...>>`의 의미: **"Gemini API 응답이라는 단일 이벤트(Mono)가 발생하면, 그 결과가 List다."**

| | Mono<List<T>> | Flux<T> |
|---|---|---|
| 발행 횟수 | 딱 1번 (리스트를 한 방에) | N번 (아이템을 하나씩 흘려보냄) |
| 적합한 경우 | API 응답 1건 = 분석 결과 묶음 | Kafka 메시지 스트림, SSE |
| 오늘 코드 | `GeminiAnalyzerService.analyzeBatch()` | `ChatAnalysisProcessor`의 Kafka 스트림 |

---

### Q. `Mono.fromCallable()`이 하는 일은?

**블로킹 코드를 리액티브 파이프라인에 안전하게 끼워 넣는 브릿지**입니다.

```java
// Mono.fromCallable()은 "이 람다를 구독될 때 실행하겠다"는 약속을 담은 Mono를 만들어줌
return Mono.fromCallable(() -> {
    // ← 지금 이 순간은 실행 안 됨
    // ← .subscribe() 또는 .block() 이 호출될 때 실행됨
    return expensiveOperation();
});
```

실제 Gemini WebClient 연동 후에는 이렇게 바뀝니다:
```java
// fromCallable 없이 WebClient 자체가 Mono를 반환
return webClient.post()
    .bodyValue(buildPrompt(chats))
    .retrieve()
    .bodyToMono(GeminiResponse.class) // ← 여기서 바로 Mono 반환
    .map(response -> parseResult(response));
```

> 현재 `fromCallable` + `Thread.sleep()`은 WebClient 연동 전 임시 시뮬레이션입니다.



```java
// ChatStreamService.java
public Flux<Object> subscribeRoom(String roomId) {
    return sink.asFlux();  // 무한히 흘러오는 데이터 스트림
}
```

`Flux`는 **무한히 발행될 수 있는 데이터 파이프**입니다. SSE처럼 끊기지 않는 스트림에 적합합니다.

## 처리 체인 패턴

```java
kafkaReceiver
    .receive()                              // Flux<ReceiverRecord>
    .map(record -> parse(record))           // 변환
    .bufferTimeout(50, Duration.ofSeconds(1)) // 50개 또는 1초마다 묶기
    .filter(batch -> !batch.isEmpty())      // 빈 배치 제거
    .flatMap(batch -> analyze(batch))       // 비동기 분석
    .subscribe();                           // 실행 시작!
```

> **중요:** `.subscribe()` 전까지는 아무것도 실행되지 않습니다. Reactor는 **선언적**입니다.

---

# 📌 4. Resilience4j — Circuit Breaker (서킷 브레이커)

## 전기 차단기 패턴

실제 전기 차단기처럼, 외부 API가 연속으로 실패하면 회로를 차단합니다.

```
정상 상태 (Closed)  →  실패율 초과  →  차단 (Open)  →  일정 시간 후  →  테스트 (Half-Open)
                                         ↓
                                   fallback 메서드 실행
```

## 프로젝트 적용

```java
// GeminiAnalyzerService.java
@CircuitBreaker(name = "geminiApi", fallbackMethod = "fallbackAnalyzeBatch")
public Mono<List<AnalyzedChatMessage>> analyzeBatch(List<RawChatMessage> chats) {
    // Gemini API 호출 (실패 가능)
}

// API 실패 시 자동으로 이 메서드가 호출됨
public Mono<List<AnalyzedChatMessage>> fallbackAnalyzeBatch(List<RawChatMessage> chats, Throwable t) {
    // NEUTRAL 감정으로 임시 응답 → 파이프라인 중단 방지
    return Mono.just(chats.stream().map(chat -> 
        AnalyzedChatMessage.builder().emotion(Emotion.builder().type("NEUTRAL").build()).build()
    ).toList());
}
```

**효과:** AI API가 다운돼도 채팅 파이프라인 전체가 멈추지 않습니다.

---

# 📌 5. R2DBC — 논블로킹 DB 연동

## JDBC vs R2DBC

| | JDBC | R2DBC |
|--|------|-------|
| 방식 | 블로킹 (결과 올 때까지 대기) | 논블로킹 (결과 오면 콜백) |
| 스레드 | 쿼리당 1스레드 점유 | 최소 스레드로 다수 쿼리 처리 |
| 결과 타입 | `List<T>` | `Flux<T>`, `Mono<T>` |

## 프로젝트 적용

```java
// ChatStreamService.java
analyzedChatRepository.save(entity)          // Mono<AnalyzedChat> 반환
    .subscribeOn(Schedulers.boundedElastic()) // I/O 전용 스레드 풀 사용
    .subscribe(
        saved -> log.debug("Saved: {}", saved.getId()),
        error -> log.error("DB Error: {}", error.getMessage())
    );
```

`subscribeOn(Schedulers.boundedElastic())`은 **블로킹 가능성이 있는 I/O 작업**을 전용 스레드 풀로 보내는 패턴입니다. Netty의 이벤트 루프 스레드를 블로킹하지 않기 위해 필수입니다.

---

# 📌 6. Redis Reactive — 실시간 통계 집계

## Hash 구조 선택 이유

감정별 카운트를 저장하는 방법:

| 방법 | Key 수 | 조회 복잡도 |
|------|--------|------------|
| 각각의 String Key | `room:id:POSITIVE`, `room:id:NEGATIVE`... | O(n) — n번 조회 |
| Hash 구조 | `room:id:stats` 1개 | O(1) — 1번 조회로 전체 필드 |

```
Redis Hash: room:test-room-1:stats
┌─────────────┬───────┐
│ POSITIVE    │  14   │
│ NEGATIVE    │   2   │
│ NEUTRAL     │   4   │
│ TOTAL_COUNT │  20   │
└─────────────┴───────┘
```

## 적용 코드

```java
// StreamRedisService.java
public Mono<Boolean> incrementEmotionStats(String roomId, String emotionType) {
    String key = "room:" + roomId + ":stats";
    return reactiveRedisTemplate.opsForHash()
        .increment(key, emotionType, 1)   // HINCRBY — 원자적 증가
        .then(reactiveRedisTemplate.opsForHash().increment(key, "TOTAL_COUNT", 1))
        .thenReturn(true);
}
```

`HINCRBY`는 **원자적 연산**입니다. 여러 요청이 동시에 들어와도 Race Condition 없이 안전하게 카운트가 증가합니다.

---

# 📌 7. SSE (Server-Sent Events) — 서버→클라이언트 단방향 스트림

## WebSocket vs SSE

| | WebSocket | SSE |
|--|-----------|-----|
| 방향 | 양방향 | 서버→클라이언트 단방향 |
| 프로토콜 | 별도 WS 프로토콜 | 일반 HTTP |
| 재연결 | 직접 구현 | 브라우저 자동 재연결 |
| 용도 | 채팅, 게임 | 알림, 실시간 피드 |

실시간 감정 통계 **대시보드**처럼 서버가 클라이언트에게 일방적으로 데이터를 밀어주는 용도라면 SSE가 훨씬 단순합니다.

## SSE 이벤트 형식

```
event: chat_analyzed          ← 이벤트 이름
data: {"content": "안녕"}     ← JSON 데이터
                              ← 빈 줄이 이벤트 구분자
event: stats_update
data: {"TOTAL_COUNT": 10}
```

## Spring WebFlux에서 SSE 구현

```java
// StreamController.java
@GetMapping(value = "/stream/{roomId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Object>> streamChatAnalysis(@PathVariable String roomId) {

    // 1. 실제 데이터 스트림
    Flux<ServerSentEvent<Object>> dataStream = chatStreamService.subscribeRoom(roomId)
        .map(payload -> ServerSentEvent.builder()
            .event("chat_analyzed")
            .data(payload)
            .build());

    // 2. 연결 유지용 ping (15초마다)
    Flux<ServerSentEvent<Object>> pingStream = Flux.interval(Duration.ofSeconds(15))
        .map(i -> ServerSentEvent.builder()
            .event("ping")
            .data("keep-alive")
            .build());

    // 3. 두 스트림 병합 — 먼저 오는 이벤트부터 전송
    return Flux.merge(dataStream, pingStream);
}
```

`produces = TEXT_EVENT_STREAM_VALUE`를 선언하면 Spring이 자동으로 HTTP 응답을 SSE 형식으로 직렬화합니다.

---

# 📌 8. Reactor Sinks — 명령형→반응형 브릿지

## 문제: @KafkaListener는 명령형 코드

`@KafkaListener`는 일반 Java 메서드입니다. 그런데 SSE는 `Flux` (Reactive) 기반입니다.
이 둘을 어떻게 연결하나요?

## 해결: Sinks.Many — 데이터를 집어넣는 "파이프 입구"

```
[@KafkaListener] → Sinks.Many ← 데이터 push
                       ↓
                  sink.asFlux() → SSE Flux → 클라이언트
```

```java
// ChatStreamService.java
private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();

// Kafka 메시지 수신 시 (명령형)
@KafkaListener(...)
public void consume(AnalyzedChatMessage message) {
    Sinks.Many<Object> sink = roomSinks.get(message.getRoomId());
    if (sink != null) {
        sink.tryEmitNext(message);  // ← 파이프에 데이터 밀어넣기
    }
}

// SSE 구독 시 (반응형)
public Flux<Object> subscribeRoom(String roomId) {
    Sinks.Many<Object> sink = roomSinks.computeIfAbsent(roomId,
        key -> Sinks.many().replay().limit(100)); // ← 파이프 출구
    return sink.asFlux();
}
```

## multicast vs replay

| | `multicast()` | `replay(n)` |
|--|---------------|-------------|
| 구독 전 데이터 | **버림** ❌ | **n개 버퍼링** ✅ |
| 용도 | 실시간 현재 데이터만 | 늦게 구독해도 최근 n개 수신 |

SSE는 클라이언트가 언제 연결할지 모르므로 반드시 `replay()`를 써야 합니다.

---

# 📌 9. JsonSerializer 타입 헤더 — 모듈 간 역직렬화 충돌

## 문제

`spring-kafka`의 `JsonSerializer`는 기본적으로 메시지 헤더에 `__TypeId__`를 추가합니다.

```
Kafka 메시지 헤더:
  __TypeId__: com.neul.analyzer.dto.AnalyzedChatMessage  ← analyzer 패키지
```

`core-api`의 consumer가 이 헤더를 보면 `com.neul.analyzer.dto.*` 패키지에서 클래스를 찾으려 합니다. 하지만 `core-api`에는 해당 패키지가 없습니다!

## 해결

```java
// analyzer KafkaConfig.java (Producer 측)
props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false); // 헤더 추가 안 함
```

```java
// core-api KafkaConsumerConfig.java (Consumer 측)
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
    AnalyzedChatMessage.class.getName()); // 항상 이 타입으로 역직렬화
props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
```

**원칙:** 서로 다른 모듈 간 Kafka 직렬화 시 `ADD_TYPE_INFO_HEADERS=false` + `VALUE_DEFAULT_TYPE` 명시가 안전합니다.

---

# 🔗 전체 데이터 흐름 정리

```
[NidChatCollector]
  실시간 웹소켓 수집 → KafkaTemplate.send("raw-chat-batch-topic", roomId, RawChatBatch)
                                          │
                                    ┌─────▼──────┐
                                    │  Kafka     │  raw-chat-topic (3 partitions)
                                    │  (버퍼)    │  파티션 키 = roomId → 순서 보장
                                    └─────┬──────┘
                                          │
[@KafkaListener, MAX_POLL_RECORDS=50]     │
  최대 50개씩 배치 수신                    │
  → JSON 파싱 (ObjectMapper)             │
  → ChatAnalysisProcessor.processBatch() │
    (@CircuitBreaker — 실패 시 NEUTRAL)   │
  → KafkaTemplate.send("analyzed-chat-topic", roomId, AnalyzedChatMessage)
                                          │
                                    ┌─────▼──────┐
                                    │  Kafka     │  analyzed-chat-topic (3 partitions)
                                    └─────┬──────┘
                                          │
[@KafkaListener, core-api]                │
  analysedChatRepository.save(entity)    │  → PostgreSQL (R2DBC 비동기)
  streamRedisService.increment(...)      │  → Redis HINCRBY (통계 누적)
  sink.tryEmitNext(message)              │  → Sinks.Many<Object>
                                          │
                                    ┌─────▼──────────────────────┐
                                    │  SSE Flux                   │
                                    │  /api/v1/stream/{roomId}    │
                                    │  event: chat_analyzed       │
                                    │  event: stats_update        │
                                    │  event: ping (15s keep-alive)│
                                    └─────────────────────────────┘
                                          │
                                    [curl / 브라우저]
```
