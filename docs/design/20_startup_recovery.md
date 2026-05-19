# 서비스 기동 순서 및 재복구 전략

작성일: 2026-05-02

## 1. 의존 관계도

```
PostgreSQL ──┐
Redis       ──┤──► core-api (8083)
Kafka       ──┤                  └──► collector (8081)
              │                            │
              └──► analyzer (8082) ◄───────┘
                        │
                      Ollama (11434)
```

| 서비스 | 필수 의존 | 선택 의존 |
|---|---|---|
| `core-api` | PostgreSQL (Flyway), Redis (SlotService), Kafka (이벤트 컨슈머) | — |
| `collector` | Kafka (completion/failure 컨슈머) | core-api (highlight fallback WebClient) |
| `analyzer` | Kafka (raw-chat-batch 컨슈머), Ollama (LLM 호출) | — |

## 2. 정상 기동 순서

```
1. 인프라 레이어
   PostgreSQL → Redis → Kafka (또는 Zookeeper → Kafka)

2. Ollama 레이어 (별도 터미널)
   ollama serve  [모델이 없으면: ollama pull gemma:2b]

3. 애플리케이션 레이어 (순서 무관하지만 아래를 권장)
   core-api → collector → analyzer
```

## 3. 꼬인 순서별 증상과 복구

### 3-1. core-api가 PostgreSQL보다 먼저 기동

**증상**
```
FlywayException: Unable to obtain connection from database
```
Spring 컨텍스트가 시작하지 못하고 즉시 종료된다.

**복구**
```bash
# PostgreSQL 기동 확인 후 core-api 재시작
docker compose up -d postgres
# core-api 프로세스 재기동
```

---

### 3-2. core-api가 Redis보다 먼저 기동

**증상**
- 앱은 뜨지만 첫 VOD 분석 요청 시 `VodAnalysisSlotService.tryAcquire()`에서 예외 발생
- fail-open 전략으로 `ACQUIRED`를 반환하므로 분석은 허용되나 슬롯 카운터가 갱신되지 않음
- 로그: `[VodAnalysisSlotService] Redis error → fail-open`

**복구**
- Redis가 기동되면 `ReactiveRedisConnectionFactory`가 자동으로 재연결을 시도한다.
- 별도 재시작 없이 Redis를 기동하면 자연 복구된다.
- 단, Redis 장애 중 슬롯 카운터 정합성이 깨졌다면 TTL 30분 후 자동 만료된다.

```bash
docker compose up -d redis
# 별도 core-api 재시작 불필요
```

---

### 3-3. collector / analyzer가 Kafka보다 먼저 기동

**증상**
```
org.apache.kafka.common.errors.TimeoutException: Topic ... not present in metadata
```
컨슈머 그룹 등록 실패. Spring Boot는 기본적으로 Kafka 연결을 재시도하지만
`spring.kafka.consumer.auto-offset-reset` 설정과 retry backoff에 따라 다르다.

**복구**
```bash
docker compose up -d kafka
# Kafka 연결 복구 후 컨슈머가 자동으로 재연결을 시도한다.
# 재시도가 소진된 경우에는 서비스를 재시작한다.
```

---

### 3-4. analyzer가 Ollama보다 먼저 기동

**증상**
- 앱은 정상 기동
- 첫 LLM 호출 시 `Connection refused` → CircuitBreaker OPEN
- 로그: `[OllamaAnalyzerService] LLM call failed, circuit breaker may open`
- OPEN 상태에서는 이후 배치도 즉시 실패하며 `gak.llm.batch.skipped` 카운터 증가

**복구**
```bash
# Ollama 기동 (모델 없으면 먼저 pull)
ollama serve
ollama pull gemma:2b   # 최초 1회만

# CircuitBreaker는 설정된 waitDurationInOpenState 후 HALF_OPEN으로 전환되어 자동 복구된다.
# 즉시 복구가 필요하면 analyzer 재시작.
```

---

### 3-5. collector가 core-api보다 먼저 기동

**증상**
- 앱은 정상 기동
- `GET /status` 에서 highlight fallback 호출 시 `Connection refused`
- `onErrorResume`으로 처리되어 현재 in-memory status를 그대로 반환
- 기능 손실 없음 — 단지 ANALYZING 스턱 자동 복구가 동작하지 않을 뿐

**복구**
- core-api가 기동되면 자동으로 정상화된다. 재시작 불필요.

---

### 3-6. collector가 분석 진행 중에 재시작된 경우

(= in-memory 상태 소실 시나리오)

**증상**
- collector 재시작 후 `GET /status` → `IDLE` 반환
- 분석이 이미 완료됐다면 highlight fallback이 `IDLE → COMPLETED`로 자동 복구
- 분석이 아직 진행 중이라면 `IDLE` 상태로 보이지만 analyzer에서 완료 후
  Kafka 이벤트(`vod-analysis-complete-topic`)가 도착하면 `COMPLETED`로 전환됨

**복구**
- 대부분 자동 복구된다.
- 분석 완료 이벤트를 collector가 받지 못한 극단적 케이스만 30분 타임아웃 후 `FAILED` 전환.
- 사용자 입장에서는 재분석 요청 버튼으로 수동 재시도 가능.

---

## 4. 빠른 상태 점검 명령어

```bash
# 인프라 컨테이너 상태
docker compose ps

# core-api 헬스
curl -s http://localhost:8083/actuator/health | jq .status

# collector VOD 상태 확인
curl -s http://localhost:8081/api/v1/vod/{videoNo}/status

# Redis 연결 확인
redis-cli ping

# Kafka 토픽 목록
docker exec gak-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# Ollama 모델 상태
ollama list
```

## 5. 권장 로컬 기동 스크립트 순서

```bash
# 1단계: 인프라
docker compose -f backend/docker-compose.yml up -d

# 2단계: Ollama (별도 터미널)
ollama serve

# 3단계: Spring Boot 서비스 (각각 별도 터미널 또는 IDE Run)
./gradlew :core-api:bootRun
./gradlew :collector:bootRun
./gradlew :analyzer:bootRun

# 4단계: 프론트엔드
cd frontend && npm run dev
```

## 6. 향후 개선 고려사항

- **Spring Boot Actuator 헬스체크 강화**: `management.health.kafka.enabled=true` 설정으로 Kafka 연결 상태를 헬스엔드포인트에 포함
- **Resilience4j retry 설정 명시화**: 현재 각 서비스의 Kafka 재연결 backoff가 기본값. `application.yml`에 명시적으로 정의하면 운영 시 예측이 쉬워짐
- **StatusService 영속화**: collector 재시작으로 인한 상태 소실이 잦으면 Redis에 상태를 저장하는 방식으로 개선 가능 (현재는 fallback으로 커버 중)
