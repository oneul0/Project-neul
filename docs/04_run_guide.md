# 늘(Neul) 프로젝트 — 로컬 실행 가이드

> **작성일:** 2026-02-27  
> **대상:** 프로젝트 최초 실행 또는 환경 재구성 시 참고

---

## 1. 아키텍처 한눈에 보기

```
[collector :8081]
  NidChatCollector (Chzzk WebSocket) → KafkaProducer → raw-chat-batch-topic
                                               │
                                               ▼
[analyzer :8082]
  @KafkaListener(batch) → GeminiAnalyzerService → KafkaTemplate → analyzed-chat-topic
                                                                         │
                                                                         ▼
[core-api :8083]
  @KafkaListener → PostgreSQL(R2DBC) + Redis(HINCRBY) → Sinks → SSE /api/v1/stream/{roomId}
```

모든 서비스는 **독립적인 Spring Boot 앱**이며, Docker로 올라오는 인프라(Kafka, PostgreSQL, Redis)를 공유합니다.

---

## 2. 사전 준비

| 항목 | 확인 명령 | 필수 버전 |
|------|-----------|-----------|
| Docker Desktop | `docker --version` | 실행 중이어야 함 |
| JDK | `java -version` | 17 이상 |
| gradlew.bat | 프로젝트 루트에 파일 존재 여부 확인 | — |

> **gradlew.bat이 없으면?** `gradle-wrapper.jar`와 `gradle-wrapper.properties`가 `gradle/wrapper/`에 있다면  
> `gradlew.bat` 배치 스크립트만 추가하면 됩니다. → `docs/02_troubleshooting.md` 참고

---

## 3. 실행 순서

### Step 1 — 인프라 실행

```powershell
# backend 폴더에서
docker-compose up -d

# 상태 확인 (4개 컨테이너가 Up이어야 함)
docker ps
```

| 컨테이너 | 역할 | 포트 |
|----------|------|------|
| neul-postgres | 채팅 영구 저장 | 5432 |
| neul-redis | 실시간 통계 집계 | 6379 |
| neul-zookeeper | Kafka 코디네이터 | 2181 |
| neul-kafka | 메시지 브로커 | 9092 |

> ⚠️ Kafka는 기동까지 약 15~30초 소요됩니다. 컨테이너 `Up` 표시 후 잠시 기다렸다가 앱을 실행하세요.

### Step 2 — Spring Boot 서비스 실행 (순서 중요!)

**터미널 A** — analyzer 먼저 (raw-chat-topic 소비자)
```powershell
.\gradlew.bat :analyzer:bootRun
```
`Started AnalyzerApplication` 로그 확인 후 다음 단계 진행.

**터미널 B** — core-api
```powershell
.\gradlew.bat :core-api:bootRun
```

**터미널 C** — collector (마지막)
```powershell
.\gradlew.bat :collector:bootRun
```

### Step 3 — SSE 스트림 구독

**터미널 D** — 수신 대기

> ⚠️ PowerShell에서는 반드시 `curl.exe`를 사용하세요.  
> `curl`은 `Invoke-WebRequest`의 별칭이라 `-H` 옵션 처리 방식이 다릅니다.

```powershell
# 실제 치지직 채널 ID 사용 (예: 458f6ec20b034f49e0fc6d03921646d2)
curl.exe -N -H "Accept: text/event-stream" http://localhost:8083/api/v1/stream/{channelId}
```

### Step 4 — 데이터 수신 확인

터미널 D에 아래처럼 스트리밍되면 전체 파이프라인이 정상입니다.

```
event:chat_analyzed
data:{"messageId":"...","roomId":"{channelId}","content":"...","emotion":{"type":"POSITIVE","score":0.87},"analyzedAt":"..."}

event:stats_update
data:{"POSITIVE":"14","TOTAL_COUNT":"20","NEGATIVE":"2","NEUTRAL":"4"}

event:ping
data:keep-alive   ← 15초마다 연결 유지용
```

### Step 5 — 종료

```powershell
# 각 터미널에서 Ctrl+C 후:
docker-compose down

# 데이터 볼륨까지 완전 삭제 (초기화할 때)
docker-compose down -v
```

---

## 4. 현재 미완성 항목

| 항목 | 현재 상태 | 목표 |
|------|-----------|------|
| 감정 분석 | Ollama (Gemma:2b) / Gemini 연동 완료 | 분석 모델 고도화 및 다국어 지원 |
| 채팅 수집 | `NidChatCollector` 치지직 실시간 웹소켓 수집 완료 | 유튜브 등 다중 플랫폼 확장 |
| roomId 연동 | 동적 채널 ID 연동 완료 | 사용자별 대시보드 커스텀 |
| CORS | 설정 완료 | 보안 설정 강화 |
