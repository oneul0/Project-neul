# 04. 트러블슈팅

최종 업데이트: 2026-08-23

현재 구조에서 자주 발생하는 증상과 복구 순서를 정리한다. 설계 수준의 장애 전략은 [`11_system_reliability.md`](11_system_reliability.md)를 참고한다.

## 1. 가장 먼저 볼 것

문제가 생기면 아래 순서대로 확인합니다.

1. 어떤 서비스가 실제로 떠 있는지 확인
2. `frontend -> core-api -> collector -> analyzer -> Kafka/Redis/PostgreSQL` 중 어디에서 끊겼는지 확인
3. 브라우저 에러와 서버 로그를 같은 시각 기준으로 맞춰서 보기
4. DB/Flyway, Kafka consumer group, owner 인증 쿠키를 마지막까지 확인

## 2. 증상별 빠른 가이드

### 2-1. CHZZK 로그인 URL 생성 시 `${CHZZK_CLIENT_ID}`가 그대로 보일 때

원인:

- `.env`가 Spring에 import되지 않았거나
- 실행 위치에 따라 상대 경로 import가 누락된 경우

확인:

- `backend/.env` 존재 여부
- `collector`의 `application.yaml`에 `spring.config.import` 설정 여부

해결:

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
      - optional:file:../../.env[.properties]
```

### 2-2. 브라우저에는 CORS처럼 보이는데 실제로는 preflight가 막힐 때

원인:

- owner 검증 필터가 `OPTIONS` preflight까지 검사해서
- CORS 헤더가 붙기 전에 요청이 막힌 경우

대응:

- `OPTIONS`는 필터에서 통과
- 브라우저가 `8081`, `8083`을 직접 치지 않도록 Next API proxy 사용

### 2-3. `gak_app` 비밀번호 인증 실패가 날 때

원인 후보:

- Docker DB가 아니라 로컬 PostgreSQL에 붙고 있음
- 이전 볼륨에 남은 계정 상태와 현재 설정이 다름

우선 확인:

```powershell
Get-Service postgresql-x64-15
docker ps
```

현재 기준 권장:

- 로컬 PostgreSQL 서비스는 중지
- Docker Postgres만 사용
- core-api는 Flyway로 스키마를 자동 적용

### 2-4. `relation "vod_timeline_points" does not exist`

원인:

- 예전에는 `schema.sql` 수동 반영 상태였고
- 새 테이블이 DB에 반영되지 않은 채 코드만 먼저 배포된 경우

현재 상태:

- core-api는 Flyway로 관리
- `V2__add_vod_timeline_points.sql`이 자동 적용되어야 함

확인:

- `core-api` 부팅 로그에서 Flyway migrate 성공 여부

### 2-5. VOD 분석 상태가 `ANALYZING`에서 안 넘어갈 때

원인 후보:

- analyzer 완료 이벤트를 collector가 못 받음
- analyzer/core-api consumer가 늦게 붙어 completion 체인이 끊김

현재 대응:

- analyzer가 `vod-analysis-complete-topic`에 완료 이벤트 발행
- collector가 완료 이벤트를 받아 `COMPLETED`로 변경
- 추가 fallback:
  - status 조회 시 현재 상태가 `ANALYZING`인데 core-api에 highlights가 이미 있으면 collector가 `COMPLETED`로 보정
  - 30분 이상 결과가 없으면 `FAILED`로 전환
  - collector 재기동으로 `IDLE`이 되어도 highlight가 있으면 `COMPLETED`로 복구

프론트 폴링 간격은 `REQUESTED=3초`, `ANALYZING=8초`, 그 외 활성 상태는 `5초`다. `COMPLETED`인데 timeline·highlight 저장이 아직 따라오지 못한 경우에는 1.5초 간격으로 최대 10회 동기화한다.

### 2-6. VOD 하이라이트가 특정 시점까지만 몰릴 때

원인:

- 크롤링이 아니라 "최종 선별 로직"에서 앞쪽 고밀도 구간이 계속 이기는 경우가 많음

현재 대응:

- 전체 상위 점수만 고르지 않음
- 시간대 버킷 대표를 먼저 확보
- 나머지 자리를 전역 상위 점수로 채움
- `transitionScore`를 넣어 조용하다가 급증한 구간 가중치 추가

### 2-7. 타임라인이 비고 하이라이트만 보일 때

원인:

- `vod_timeline_points` 저장 또는 조회가 실패했을 가능성

현재 대응:

- core-api의 `/timeline`은 실패 시 highlight 기반 fallback 반환
- frontend도 `timeline`이 비면 `highlights`로 fallback 타임라인 생성

즉 화면이 완전히 비는 문제는 막혀 있지만, 정확한 전체 타임라인을 보려면 timeline 저장 경로가 정상이어야 합니다.

## 3. 최근 주요 이슈 해결 이력

### 2026-03-31. owner 대시보드와 VOD 분석 흐름 정리

- CORS처럼 보이는 문제의 실제 원인이 preflight 차단임을 확인
- 브라우저 직접 호출을 Next proxy 구조로 전환
- VOD 조회와 분석 시작을 분리
- mock chat 주입 API 추가
- VOD 상태를 `REQUESTED / CRAWLING / ANALYZING / COMPLETED / FAILED`로 정리

### 2026-04-01. 편집 후보 중심 하이라이트 실험

- VOD 하이라이트를 감정 점수 출력이 아니라 편집 후보 탐색으로 재정의
- `intensity / transition / editability` 내부 점수 도입
- 사용자 화면에는 `추천 강도 / 추천 이유 / 대표 채팅` 중심으로 단순화
- 하이라이트 선별이 특정 시간대로 쏠리지 않도록 버킷 기반 분산 선택 적용

## 4. 로그 확인 포인트

### collector

- `[VOD-Crawler] Accepted crawl request`
- `[VOD-Crawler] Requesting chunk`
- `[VOD-Crawler] Progress`
- `[VOD-Crawler] Reached end of VOD chats`
- `[VOD-Crawler] Finished collection`

### analyzer

- `Finalized videoNo=...`
- `Timeline range videoNo=...`
- `Failed to finalize VOD highlights`

### core-api

- Flyway migrate 로그
- `[VodController] Failed to load timeline ...`
- `[VOD-Highlight-Consumer]`
- `[VOD-Timeline-Consumer]`

## 5. 기동 순서와 복구

권장 순서는 `PostgreSQL·Redis·Kafka → Ollama → core-api → collector → analyzer → frontend`다.

| 증상 | 복구 |
|------|------|
| core-api가 Flyway 오류로 종료 | PostgreSQL 기동 확인 후 core-api 재시작 |
| Redis 오류로 VOD 슬롯 카운팅 실패 | Redis 기동 후 자동 재연결. 분석은 fail-open으로 계속됨 |
| Kafka topic metadata 오류 | Kafka 기동 후 consumer 재연결 확인. 재시도가 소진됐으면 해당 서비스 재시작 |
| Ollama 연결 실패·Circuit Breaker OPEN | `ollama serve`, 모델 확인 후 HALF_OPEN 자동 복구 대기 또는 analyzer 재시작 |
| collector 재기동 후 VOD 상태가 IDLE | status 조회 시 저장된 highlight를 확인해 COMPLETED로 자동 보정 |

빠른 점검:

```bash
docker compose -f backend/docker-compose.yml ps
curl -s http://localhost:8083/actuator/health
curl -s http://localhost:8081/api/v1/vod/{videoNo}/status
curl -s http://localhost:8082/actuator/metrics/gak.llm.api.calls.total
ollama list
```

## 6. 폐기된 전제

아래 내용은 현재 구조에 적용하지 않는다.

- `schema.sql` 수동 동기화 전제
- 공개 방송 탐색형 대시보드 전제
- VOD 하이라이트를 단일 "감정 점수"로만 설명하는 표현
- 오래된 `1분 배치` 설명

현재는:

- Flyway 마이그레이션 기준
- owner 전용 대시보드 기준
- 편집 후보 중심 VOD 분석 기준
- VOD 분석은 조회/분석 시작/상태/결과를 나눈 흐름 기준
