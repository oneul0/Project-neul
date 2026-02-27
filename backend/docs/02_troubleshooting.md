# Troubleshooting & Debugging Logs
개발 및 테스트 중 발생한 주요 오류와 해결 과정을 기록합니다.

---

## [2026-02-27] 빌드 시 Gradle Wrapper 누락 및 컴파일 의존성 오류

- **오류 증상 (Symptom):** 프로젝트 의존성 검증을 위해 터미널에서 `.\gradlew clean build`를 실행했으나, 명령어를 찾을 수 없다는 에러 발생. 추가로 IDE에서 각 모듈의 코드를 인식하지 못하는 의존성 누락 문제 감지.
- **에러 로그 (Error Log):**
  ```powershell
  .\gradlew : '.\gradlew' 용어가 cmdlet, 함수, 스크립트 파일, 또는 실행할 수 있는 프로그램 이름으로 인식되지 않습니다.
  ```
  ```text
  gradle : 'gradle' 용어가 cmdlet, 함수, 스크립트 파일 위치 줄:1 문자:1.
  + gradle wrapper
  ```
- **원인 분석 (Root Cause Analysis):** 
  1. 프로젝트 루트에 `gradlew` 및 `gradlew.bat` 파일이 생성되어 있지 않았으며, 시스템 환경 변수로도 전역 `gradle` 명령어가 설정되어 있지 않음.
  2. `neul-analyzer` 및 `neul-core-api` 모듈에 Kafka Consumer/Producer를 위한 의존성, 그리고 `AnalyzedChatMessage` DTO 등에서 날짜(`LocalDateTime`)를 파싱하기 위한 Jackson JSR310 의존성이 `build.gradle`에 누락되어 있어서 컴파일 시 에러가 날 상황이었음. 
- **해결 방법 (Resolution):**
  1. `neul-core-api/build.gradle`에 `spring-boot-starter-kafka`, `jackson-databind`, `jackson-datatype-jsr310` 추가.
  2. `neul-analyzer/build.gradle`에 WebFlux 환경에서 카프카를 쓰기 위한 `reactor-kafka` 및 Jackson 모듈 추가.
  3. `application.yaml` 내부 카프카 포트를 모두 호스트 기준인 `9092`로 통일.
- **향후 예방책 (Prevention):** 스켈레톤 프로젝트를 처음 스캐폴딩할 때는 먼저 의존성 정의(build.gradle)와 로컬 환경변수(application.yml) 매핑을 모두 맞춘 후 소스 코드를 작성해야 포트 충돌이나 패키지 오류를 사전에 막을 수 있음. 프로젝트를 깃허브 등에 올릴 땐 `gradlew` 파일을 반드시 포함시켜서 커밋할 것(Zero-install build 지향).

---

## [2026-02-27] 로컬 최초 실행 — 런타임 이슈 모음

### [TBS-02] `reactor-kafka` + `kafka-clients 4.x` 버전 불일치 (`NoSuchMethodError`)

- **오류 증상 (Symptom):** `analyzer` 기동 후 Kafka 메시지 수신 시점에 JVM 크래시 발생.
- **에러 로그 (Error Log):**
  ```
  java.lang.NoSuchMethodError: 'void org.apache.kafka.clients.consumer.ConsumerRecord.<init>
  (java.lang.String, int, long, long, org.apache.kafka.common.record.TimestampType,
  java.lang.Long, int, int, java.lang.Object, java.lang.Object,
  org.apache.kafka.common.header.Headers)'
  at reactor.kafka.receiver.internals.DefaultKafkaReceiver...
  ```
- **원인 분석 (Root Cause Analysis):**
  `reactor-kafka 1.3.x`는 `kafka-clients 2.x ~ 3.x` 기준으로 빌드됨.
  `Spring Boot 4.x`의 `spring-boot-starter-kafka`가 가져오는 `kafka-clients 4.0+`에서
  `ConsumerRecord` 생성자 시그니처(11-arg → 10-arg)가 변경됨 → 런타임 불일치.
- **해결 방법 (Resolution):**
  `reactor-kafka` 의존성을 `analyzer/build.gradle`에서 완전 제거.
  `spring-kafka`의 **배치 `@KafkaListener`** + `KafkaTemplate` 방식으로 교체.
  `MAX_POLL_RECORDS=50` 설정으로 기존 `bufferTimeout(50)` 효과 유지.
  ```groovy
  // 제거
  implementation 'io.projectreactor.kafka:reactor-kafka:1.3.23'
  ```
- **향후 예방책 (Prevention):**
  Spring Boot 메이저 버전 업그레이드 시 `kafka-clients` 버전도 반드시 확인.
  `reactor-kafka`는 별도 릴리즈 주기이므로 [호환 매트릭스](https://github.com/reactor/reactor-kafka) 참고.

---

### [TBS-03] `org.springframework.kafka.core.reactive` 패키지 없음

- **오류 증상 (Symptom):** `analyzer` 컴파일 실패.
- **에러 로그 (Error Log):**
  ```
  error: package org.springframework.kafka.core.reactive does not exist
  import org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate;
  import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
  ```
- **원인 분석 (Root Cause Analysis):**
  Spring Kafka 4.x(Spring Boot 4.x 번들)에서 `org.springframework.kafka.core.reactive` 패키지 제거됨.
  해당 클래스들은 `reactor-kafka` 위에 얹는 Wrapper였으나 Spring Kafka 팀이 정책 변경.
- **해결 방법 (Resolution):**
  `reactor-kafka` 네이티브 API(`KafkaReceiver`, `KafkaSender`)로 임시 교체 후,
  [TBS-02] 발생으로 인해 최종적으로 표준 `@KafkaListener` 방식으로 교체.
- **향후 예방책 (Prevention):**
  `spring-kafka` 메이저 버전 업그레이드 시 Deprecated/Removed API 릴리즈 노트 확인.

---

### [TBS-04] `ReactiveRedisConnectionFactory` 빈 2개 충돌

- **오류 증상 (Symptom):** `core-api` 기동 실패.
- **에러 로그 (Error Log):**
  ```
  No qualifying bean of type 'ReactiveRedisConnectionFactory' available:
  expected single matching bean but found 2: reactiveRedisConnectionFactory, redisConnection
  ```
- **원인 분석 (Root Cause Analysis):**
  `RedisConfig`에서 `LettuceConnectionFactory`를 `@Bean`으로 직접 등록 + Spring Boot AutoConfiguration이
  `application.yaml`의 `spring.data.redis.*`를 읽어 동일 타입 빈(`redisConnection`) 자동 생성 → 충돌.
- **해결 방법 (Resolution):**
  `RedisConfig`의 `@Bean LettuceConnectionFactory` 선언 삭제.
  AutoConfiguration에 위임하고 `ReactiveRedisTemplate` Bean 설정만 유지.
- **향후 예방책 (Prevention):**
  `DataSource`, `ConnectionFactory` 등 Spring Boot가 AutoConfiguration으로 이미 관리하는 빈은
  재선언하지 말 것. `application.yaml` 프로퍼티로만 제어하는 것이 원칙.

---

### [TBS-05] `ObjectMapper` 빈 누락

- **오류 증상 (Symptom):** `analyzer` 기동 실패.
- **에러 로그 (Error Log):**
  ```
  Parameter 3 of constructor in ChatAnalysisProcessor required a bean of type
  'com.fasterxml.jackson.databind.ObjectMapper' that could not be found.
  ```
- **원인 분석 (Root Cause Analysis):**
  `spring-boot-starter-webflux` 존재 시 Jackson AutoConfiguration이 `ObjectMapper`를 등록해야 하나,
  Spring Boot 4.x + Resilience4j 조합에서 AutoConfiguration 초기화 순서 문제로 등록 누락.
- **해결 방법 (Resolution):**
  `WebClientConfig`에 `@Bean ObjectMapper` 명시적 선언. `JavaTimeModule` 동시 등록.
  ```java
  @Bean
  public ObjectMapper objectMapper() {
      ObjectMapper mapper = new ObjectMapper();
      mapper.registerModule(new JavaTimeModule());
      mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      return mapper;
  }
  ```
- **향후 예방책 (Prevention):**
  AutoConfiguration에 의존하지 않고 Jackson 설정이 필요한 모듈에서는 명시적으로 `@Bean` 등록.

---

### [TBS-06] SSE에서 `ping`만 수신되고 `chat_analyzed` 이벤트 없음

- **오류 증상 (Symptom):** 파이프라인이 보기에는 정상 동작하나 SSE 클라이언트에 데이터 이벤트 미수신.
- **원인 분석 (Root Cause Analysis):**
  1. **`Sinks.many().multicast()`** — 구독자(SSE 클라이언트)가 없을 때 emit된 메시지를 버림.
     Kafka 메시지가 먼저 들어오고, 이후 SSE 클라이언트가 연결되면 이미 지나간 데이터 수신 불가.
  2. **`JsonSerializer`의 `__TypeId__` 헤더** — 생산자(analyzer) 측 클래스 풀네임이 헤더에 삽입되어
     소비자(core-api) 역직렬화 시 패키지 불일치로 충돌 가능.
- **해결 방법 (Resolution):**
  ```java
  // ChatStreamService.java - multicast → replay
  // 수정 전
  Sinks.many().multicast().onBackpressureBuffer()
  // 수정 후 (최근 100개 버퍼링 → 뒤늦게 구독해도 수신 가능)
  Sinks.many().replay().limit(100)
  ```
  ```java
  // KafkaConfig.java (analyzer) - 타입 헤더 비활성화
  props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
  ```
- **향후 예방책 (Prevention):**
  SSE/WebSocket 등 실시간 구독 패턴에서 Sink를 사용할 때는 `replay()`를 기본으로 사용.
  Cross-module Kafka 직렬화 시 `ADD_TYPE_INFO_HEADERS=false` + `VALUE_DEFAULT_TYPE`으로 명시적 타입 지정.

---

### [TBS-07] PowerShell에서 `curl` 옵션 파싱 오류

- **오류 증상 (Symptom):**
  ```
  'Headers' 매개 변수를 바인딩할 수 없습니다. "System.String" 유형의 "Accept: text/event-stream" 값을
  "System.Collections.IDictionary" 유형으로 변환할 수 없습니다.
  ```
- **원인 분석 (Root Cause Analysis):**
  PowerShell에서 `curl`은 `Invoke-WebRequest`의 **별칭(alias)** 으로 동작.
  bash `curl`의 `-H "Key: Value"` 옵션 파싱 방식과 달리 헤더를 딕셔너리로 받음.
- **해결 방법 (Resolution):**
  ```powershell
  # 잘못된 방법
  curl -N -H "Accept: text/event-stream" http://...

  # 올바른 방법 — 실제 curl.exe 명시
  curl.exe -N -H "Accept: text/event-stream" http://...
  ```
- **향후 예방책 (Prevention):**
  PowerShell 환경에서 HTTP 요청 시 `curl.exe`를 명시하거나, `Invoke-WebRequest` 네이티브 문법 사용.

