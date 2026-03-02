# 늘(Neul) 프로젝트 API 테스트 가이드

이 문서는 `neul` 프로젝트의 각 마이크로서비스 모듈에서 제공하는 API 엔드포인트를 로컬 환경에서 테스트하기 위한 가이드입니다.

---

## 🚀 1. neul-chat-collector (포트: 8081)
`neul-chat-collector`는 방송의 실시간 채팅 텍스트 수집(현재는 모의 데이터 생성) 역할을 합니다.

### 1-1. 방송 수집 시작 (모의 데이터 생성 시작)
임의의 방 ID(roomId)를 생성하고 해당 방에 대해 실시간 모의 채팅 데이터를 생성하여 Kafka로 전송하기 시작합니다.

**Request:**
```http
POST http://localhost:8081/api/v1/broadcasts
```
또는 터미널 (cURL):
```bash
curl -X POST http://localhost:8081/api/v1/broadcasts
```

**Response (예시):**
```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "roomId": "e3b0c442",
    "status": "started"
  }
}
```
> **📝 참고:** 응답으로 받은 `roomId`를 이어서 `neul-core-api` 호출 시 사용합니다.

### 1-2. 방송 수집 중단 (모의 데이터 생성 중지)
모의 데이터를 더 이상 생성하지 않도록 스케줄러를 정지시킵니다.

**Request:**
```http
POST http://localhost:8081/api/v1/broadcasts/{roomId}/stop
```
또는 터미널 (cURL):
```bash
curl -X POST http://localhost:8081/api/v1/broadcasts/{roomId}/stop
```

---

## 📡 2. neul-core-api (포트: 8083)
`neul-core-api`는 클라이언트에게 실시간 스트리밍(SSE) 및 통계 데이터를 응답합니다.

### 2-1. 실시간 감정 및 전체 통계 스트리밍 구독 (SSE)
스트리밍 방식으로 연결을 유지하며, 개별 분석 채팅(`chat_analyzed`)과 누적된 감정 통계(`stats_update`) 이벤트를 실시간으로 받아옵니다. 여기서 `{roomId}` 자리에 1-1에서 획득한 아이디를 넣습니다.

**Request:**
```http
GET http://localhost:8083/api/v1/stream/{roomId}
Accept: text/event-stream
```
또는 터미널 (cURL - SSE 구독):
```bash
curl -N -H "Accept: text/event-stream" http://localhost:8083/api/v1/stream/{roomId}
```

**Response Event (Server-Sent Events 예시):**
- **이벤트 이름:** `chat_analyzed` (개별 채팅 데이터)
```json
event: chat_analyzed
data: {"messageId":"...","roomId":"e3b0c442","content":"안녕!","emotion":{"type":"POSITIVE","score":0.7},"analyzedAt":"2026-02-27T10:00:00"}
```
- **이벤트 이름:** `stats_update` (누적 통계 업데이트)
```json
event: stats_update
data: {"TOTAL_COUNT":125,"POSITIVE":80,"NEGATIVE":10,"NEUTRAL":35}
```
- **이벤트 이름:** `ping` (Keep-Alive 용도, 약 15초마다 1번 전송)
```json
event: ping
data: "keep-alive"
```

---

## 🧪 테스트 시나리오 순서 요약
1. 백그라운드 인프라(PostgreSQL, Redis, Kafka, Zookeeper)가 `docker-compose up -d`로 백그라운드 구동 중인지 확인합니다.
2. `neul-analyzer`, `neul-core-api`, `neul-chat-collector` 세 개의 Spring Boot 애플리케이션을 모두 실행합니다.
3. 터미널 창을 열고 SSE 스트림을 구독 대기시킵니다. (`curl -N ... /api/v1/stream/testRoom123` 등 임의의 고정 아이디도 사용 가능)
4. 다른 터미널 혹은 API 클라이언트(Postman 등)로 **1-1. 방송 수집 시작** API를 호출합니다 (이때, 3번에서 고정한 아이디를 사용할거면 코드 내 UUID 부분을 수정하거나, 응답받은 UUID로 3번을 다시 호출해야 합니다).
5. SSE 스트림을 열어둔 터미널 창에 실시간으로 데이터가 `event:` 포맷으로 푸시되는지 관찰합니다.
