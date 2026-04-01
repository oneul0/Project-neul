# 2026-03-31 작업 정리

## 🎯 목표 (Goal)

한 문장으로 요약: 스트리머 본인이 사용하는 오너 인증 대시보드와 VOD 하이라이트 분석 흐름을 실제 서비스처럼 연결하고 안정화하는 것.

조금 더 풀어 설명하면, 이날의 목표는 단순히 "기능 추가"가 아니었다.
이미 로그인, 대시보드, VOD 분석, 하이라이트 출력 같은 개별 기능은 어느 정도 존재하고 있었지만,
실제로 사용자가 이 흐름을 따라 들어왔을 때는 아래와 같은 문제가 계속 드러났다.

- 로그인은 되지만 어디로 가야 하는지 명확하지 않다.
- owner 인증이 풀리면 화면이 자연스럽게 상태를 반영하지 못한다.
- VOD 조회와 분석 시작의 역할이 섞여 있다.
- 하이라이트는 뜨더라도 분석 중인지 끝났는지 알기 어렵다.
- 방송 전체 흐름과 하이라이트 결과 사이 연결이 약하다.

그래서 이날의 핵심 목표는 아래처럼 정리할 수 있었다.

- CHZZK 로그인부터 내 채널 대시보드 진입까지의 흐름 정리
- owner 인증/세션 유지 안정화
- VOD 조회와 분석 시작 역할 분리
- 분석 진행 상태를 사용자에게 보이게 만들기
- 타임라인과 하이라이트를 실제로 활용 가능한 형태로 연결하기

---

## 1. 🏗️ 아키텍처 및 설계 (Architecture & Design)

### 어떤 구조로 만들었는가

이번 작업에서 정리한 전체 구조는 아래와 같다.

- `frontend (Next.js)`
  - 사용자 진입점
  - 로그인 버튼, 대시보드 UI, VOD 조회/분석 UI 제공
  - 내부 API 프록시를 통해 collector/core-api와 연결

- `collector (Spring WebFlux)`
  - CHZZK OAuth 로그인 처리
  - 세션 저장 및 갱신
  - 라이브 채팅 수집
  - VOD 채팅 수집
  - 분석 상태 관리

- `analyzer`
  - 수집된 채팅 데이터를 분석
  - 하이라이트 후보 계산
  - VOD 타임라인 및 하이라이트 이벤트 발행

- `core-api`
  - 분석 결과 저장
  - 하이라이트/타임라인 조회 API 제공
  - owner 기반 접근 제어

- `Redis`
  - CHZZK 인증 세션 저장
  - 재시작 이후에도 세션 유지에 활용

- `PostgreSQL`
  - analyzed chats
  - VOD highlights
  - VOD timeline points
  저장

- `Kafka`
  - collector → analyzer → core-api 흐름 연결
  - 분석 완료 이벤트 전달

### 데이터 흐름

#### 오너 인증 흐름

`Browser -> Next API Proxy -> Collector OAuth -> Redis Session -> /me -> Dashboard`

보다 구체적으로는:

1. 사용자가 프론트에서 로그인 버튼 클릭
2. Next 내부 API가 collector 로그인 엔드포인트를 프록시
3. collector가 CHZZK OAuth를 처리
4. 인증 결과를 Redis 세션으로 저장
5. 이후 프론트는 `/api/chzzk/me`를 통해 로그인 상태 확인
6. 로그인 상태가 확인되면 내 채널 대시보드로 이동

#### VOD 분석 흐름

`Frontend Query -> Core API -> Collector Crawl -> Kafka -> Analyzer -> Kafka -> Core API -> Frontend`

세부 단계:

1. 프론트에서 VOD 조회
2. 메타데이터 확인 후 카드 표시
3. 사용자가 분석 시작 버튼 클릭
4. core-api가 collector에 VOD 크롤링 요청
5. collector가 CHZZK VOD 채팅을 순차적으로 수집
6. 수집 결과를 Kafka `vod-raw-chat-topic`으로 발행
7. analyzer가 윈도우별 점수 계산 및 하이라이트 계산
8. 타임라인과 하이라이트를 각각 Kafka 토픽으로 발행
9. core-api가 DB에 저장
10. 프론트가 `/status`, `/timeline`, `/highlights`를 주기적으로 조회

### 핵심 설계 결정

#### 1. 브라우저가 backend를 직접 치지 않게 프록시 구조 채택

처음에는 브라우저가 `localhost:8081`, `localhost:8083`을 직접 호출하는 흐름이 있었는데,
이 방식은 아래 단점이 있었다.

- 내부 API 주소가 그대로 노출된다
- CORS / preflight 이슈가 자주 생긴다
- 인증 쿠키와 owner 관련 헤더 전달이 복잡해진다

그래서 Next 내부 API 프록시를 두는 방향으로 정리했다.

#### 2. owner 인증을 단순 로그인 여부가 아니라 "대시보드 상태"로 다루기

이전에는 로그인 성공 자체에 초점이 있었지만,
실제 서비스처럼 쓰려면 아래도 중요했다.

- 세션 만료 직전 자동 갱신
- `401`이 오면 화면 전체 owner 상태 전환
- 다른 채널 접근 시 제한 메시지 명확화

즉 "로그인 성공"이 아니라 "계속 owner 상태로 사용할 수 있는가"를 기준으로 구조를 정리했다.

#### 3. VOD 조회와 분석 시작을 분리

이 결정은 UX 측면에서 특히 중요했다.

조회와 분석이 섞여 있으면 사용자는 아래를 구분하기 어렵다.

- 내가 지금 VOD 존재 여부를 확인한 건지
- 실제 분석이 시작된 건지
- 결과가 없는 건지 아직 계산 중인 건지

그래서 시나리오를 분리했다.

- 조회: 메타데이터 확인
- 분석 시작: 실제 백엔드 처리 시작
- 상태: 분석 진행 여부 확인
- 결과: 저장된 하이라이트 확인

#### 4. 세션 저장소를 메모리에서 Redis로 이동

세션이 메모리 기반이면 다음 문제가 생긴다.

- 서버 재시작 시 세션 유실
- 멀티 인스턴스 확장 어려움
- 세션 상태와 사용자 체감이 쉽게 어긋남

그래서 Redis 기반 세션 저장소로 이동했다.

#### 5. 스키마 수동 적용 대신 Flyway 도입

`vod_timeline_points` 같은 신규 테이블이 생길 때마다
"코드는 있는데 DB는 없는" 상태가 반복됐다.

이 문제를 막기 위해:

- `schema.sql` 기반 수동 초기화
대신
- Flyway 마이그레이션 기반 버전 관리
로 전환했다.

---

## 2. 💥 트러블슈팅 (Troubleshooting)

### 문제 1: `${CHZZK_CLIENT_ID}`가 문자열 그대로 남아 로그인 URL 생성이 깨짐

**Situation**

CHZZK 로그인 엔드포인트 호출 시 `Invalid character '{' for QUERY_PARAM in "${CHZZK_CLIENT_ID}"` 에러가 발생했다.
즉 실제 client id가 주입되지 않고 placeholder 문자열이 그대로 남아 URI를 만들다가 실패한 상태였다.

**Task**

환경변수 로딩 경로와 `.env` 반영 방식을 정리해서,
로컬 실행 환경에서도 OAuth 관련 값이 안정적으로 주입되도록 해야 했다.

**Action**

- collector의 Spring config import 경로를 확장해서 `.env`를 읽도록 정리했다.
- redirect URI를 현재 컨트롤러 기준 경로로 맞췄다.
- PowerShell 세션 환경변수, `.env`, Spring placeholder 간 불일치 여부를 함께 점검했다.

**Result**

- `${CHZZK_CLIENT_ID}` 문자열이 남는 현상이 해결되었다.
- 로그인 URL 생성이 정상화되었고, CHZZK 로그인 화면으로 이동 가능해졌다.

복붙용 설정:

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
      - optional:file:../../.env[.properties]
```

```env
CHZZK_CLIENT_ID=실제클라이언트아이디
CHZZK_CLIENT_SECRET=실제클라이언트시크릿
CHZZK_REDIRECT_URI=http://localhost:8081/api/v1/chzzk/callback
```

### 문제 2: CORS처럼 보였지만 실제로는 인증 필터가 preflight를 막고 있었음

**Situation**

프론트에서 `subscribe` 요청을 보내면 브라우저에는 CORS 에러처럼 보였다.
처음에는 `Access-Control-Allow-Origin` 문제라고 생각하기 쉬운 상황이었다.

**Task**

겉으로 드러난 에러가 아니라 실제 요청 체인을 따라가며,
CORS 설정 문제인지, 그 전에 요청이 막히는지 확인해야 했다.

**Action**

- `OPTIONS preflight` 요청 흐름을 확인했다.
- owner 검증 필터가 `OPTIONS`까지 검사하고 있어 preflight를 통과시키지 못하는 걸 확인했다.
- 동시에 프론트에서 backend를 직접 호출하는 구조를 줄이기 위해 Next 내부 API 프록시를 도입했다.

**Result**

- "CORS 에러처럼 보이던" 문제의 실제 원인을 확인했다.
- 프록시 구조 도입으로 내부 backend 주소 노출과 브라우저 직접 호출을 줄였다.

복붙용 프록시 예시:

```ts
export async function POST(
  _request: Request,
  { params }: { params: Promise<{ channelId: string }> },
) {
  const { channelId } = await params;
  return fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
    method: "POST",
    headers: {
      cookie: (await cookies()).toString(),
    },
  });
}
```

### 문제 3: Docker DB를 쓰는 줄 알았는데 실제로는 로컬 PostgreSQL에 붙고 있었음

**Situation**

R2DBC 인증 실패와 스키마 누락 문제가 반복됐다.
Docker를 내려도 해결되지 않아서 이상했는데, 알고 보니 애플리케이션이 로컬 PostgreSQL 서비스에 붙고 있었다.

**Task**

프로젝트가 실제로 어떤 PostgreSQL 인스턴스를 바라보고 있는지 먼저 명확히 해야 했다.

**Action**

- Windows 서비스 상태와 Docker 컨테이너 상태를 같이 확인했다.
- 5432 포트를 누가 점유하고 있는지 확인했다.
- 로컬 PostgreSQL 서비스가 살아 있어서 Docker DB 대신 그쪽에 연결되고 있다는 걸 파악했다.

**Result**

- Docker 재기동으로 해결되지 않던 이유를 이해할 수 있었다.
- 이후 DB 스키마 문제를 구조적으로 해결하기 위해 Flyway 도입으로 이어졌다.

복붙용 점검 명령:

```powershell
Get-Service postgresql-x64-15
docker ps
```

### 문제 4: `vod_timeline_points does not exist` 에러

**Situation**

코드에서는 타임라인 저장/조회 로직을 사용하고 있었지만, DB에는 해당 테이블이 없어 런타임 에러가 발생했다.

**Task**

스키마 변경이 수동 적용에 의존하지 않도록 만들어야 했다.
도커로 띄우든 로컬에서 띄우든 애플리케이션 실행 시 항상 최신 스키마가 맞아야 했다.

**Action**

- `schema.sql` 기반 수동 초기화 대신 Flyway 마이그레이션을 도입했다.
- 기존 스키마는 `V1`
- `vod_timeline_points` 추가는 `V2`
- 편집 점수 필드 확장은 `V3`
로 나눴다.
- `baseline-on-migrate`를 켜서 기존 데이터베이스도 수용하게 했다.

**Result**

- 신규 테이블 누락 때문에 런타임이 깨지는 상황을 줄일 수 있었다.
- 스키마 변경을 코드와 함께 추적할 수 있는 구조가 됐다.

복붙용 설정:

```yaml
spring:
  flyway:
    enabled: true
    url: jdbc:postgresql://127.0.0.1:5432/neul_db
    user: neul_user
    password: neul_password
    baseline-on-migrate: true
    baseline-version: 1
    locations: classpath:db/migration
```

복붙용 마이그레이션 예시:

```sql
CREATE TABLE IF NOT EXISTS vod_timeline_points (
    id                BIGSERIAL PRIMARY KEY,
    video_no          VARCHAR(255) NOT NULL,
    start_seconds     INTEGER NOT NULL,
    end_seconds       INTEGER NOT NULL,
    message_count     INTEGER NOT NULL,
    participant_count INTEGER NOT NULL,
    activity_score    DOUBLE PRECISION NOT NULL,
    category          VARCHAR(100),
    top_message       TEXT,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 문제 5: VOD 분석 상태가 `ANALYZING`에서 끝나지 않음

**Situation**

collector는 VOD 채팅 수집을 끝냈는데, 화면 상태는 계속 `ANALYZING`에 머물렀다.
하이라이트가 일부 보이는데도 완료 상태로 전환되지 않아서 사용자 경험이 어색했다.

**Task**

분석 완료 시점을 collector도 알 수 있어야 했고,
Kafka 타이밍이 조금 어긋나도 상태가 복구되도록 해야 했다.

**Action**

- analyzer가 최종 결과 발행 후 `vod-analysis-complete-topic`으로 완료 이벤트를 보내도록 했다.
- collector가 이 이벤트를 받아 `COMPLETED`로 상태 전환하도록 했다.
- 추가로, 완료 이벤트를 놓쳐도 core-api에 하이라이트가 저장돼 있으면 status 조회 시 fallback으로 완료 처리하도록 보강했다.

**Result**

- `ANALYZING` 상태가 영원히 끝나지 않던 문제가 완화되었다.
- 시스템 간 타이밍이 조금 꼬여도 사용자에게는 더 안정적으로 완료 상태가 보이게 됐다.

복붙용 핵심 코드:

```java
@KafkaListener(topics = "vod-analysis-complete-topic", groupId = "neul-collector-vod-complete-group")
public void consumeCompletion(String json) {
    VodAnalysisCompletedEvent event = objectMapper.readValue(json, VodAnalysisCompletedEvent.class);
    var current = vodAnalysisStatusService.getStatus(event.getVideoNo());
    vodAnalysisStatusService.markCompleted(
            event.getVideoNo(),
            current.pagesProcessed(),
            current.chatsCollected()
    );
}
```

```java
@GetMapping("/{videoNo}/status")
public Mono<VodAnalysisStatusResponse> getStatus(@PathVariable String videoNo) {
    VodAnalysisStatusResponse current = vodAnalysisStatusService.getStatus(videoNo);
    if (!"ANALYZING".equals(current.status())) {
        return Mono.just(current);
    }

    return coreApiWebClient.get()
            .uri("/api/v1/vod/{videoNo}/highlights", videoNo)
            .retrieve()
            .bodyToFlux(Object.class)
            .take(1)
            .hasElements()
            .map(hasHighlights -> {
                if (hasHighlights) {
                    vodAnalysisStatusService.markCompleted(
                            videoNo,
                            current.pagesProcessed(),
                            current.chatsCollected()
                    );
                    return vodAnalysisStatusService.getStatus(videoNo);
                }
                return current;
            });
}
```

---

## 3. 💻 핵심 구현 코드 (Key Implementation)

### 1. Next 내부 API 프록시로 collector 직접 호출 감추기

```ts
export async function POST(
  _request: Request,
  { params }: { params: Promise<{ channelId: string }> },
) {
  const { channelId } = await params;
  return fetch(`http://localhost:8081/api/v1/channels/${channelId}/subscribe`, {
    method: "POST",
    headers: {
      cookie: (await cookies()).toString(),
    },
  });
}
```

### 2. 세션 저장을 Redis 기반으로 유지

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

### 3. Flyway 기반 스키마 관리

```yaml
spring:
  flyway:
    enabled: true
    url: jdbc:postgresql://127.0.0.1:5432/neul_db
    user: neul_user
    password: neul_password
    baseline-on-migrate: true
    baseline-version: 1
    locations: classpath:db/migration
```

### 4. VOD 분석 완료 이벤트 기반 상태 전환

```java
@KafkaListener(topics = "vod-analysis-complete-topic", groupId = "neul-collector-vod-complete-group")
public void consumeCompletion(String json) {
    VodAnalysisCompletedEvent event = objectMapper.readValue(json, VodAnalysisCompletedEvent.class);
    var current = vodAnalysisStatusService.getStatus(event.getVideoNo());
    vodAnalysisStatusService.markCompleted(
            event.getVideoNo(),
            current.pagesProcessed(),
            current.chatsCollected()
    );
}
```

### 5. 완료 이벤트를 놓쳤을 때의 fallback 보정

```java
return coreApiWebClient.get()
        .uri("/api/v1/vod/{videoNo}/highlights", videoNo)
        .retrieve()
        .bodyToFlux(Object.class)
        .take(1)
        .hasElements()
        .map(hasHighlights -> {
            if (hasHighlights) {
                vodAnalysisStatusService.markCompleted(
                        videoNo,
                        current.pagesProcessed(),
                        current.chatsCollected()
                );
                return vodAnalysisStatusService.getStatus(videoNo);
            }
            return current;
        });
```

---

## 4. 💡 회고 및 배운 점 (Insights)

- 로그인은 "성공 여부"보다 "계속 안정적으로 쓸 수 있는가"가 더 중요했다.
- 브라우저가 backend를 직접 치는 구조는 빨리 만들 수는 있지만, 인증/CORS/상태 관리가 쉽게 꼬인다.
- VOD 기능은 조회/분석/상태/결과가 분리될수록 사용자가 덜 헷갈린다.
- DB 스키마는 수동 적용으로 버티기보다 초기에 버전 관리 체계를 잡는 것이 낫다.
- 시스템이 여러 개로 나뉠수록 "완료 신호"를 누가 언제 알고 있는지 설계하는 것이 중요하다.

---

## 🚀 다음 단계 (Next Steps)

- VOD 하이라이트를 감정 분석보다 편집 후보 탐색 관점으로 재정의
- 점수 체계를 더 세분화
- 추천 이유를 더 이해하기 쉬운 문장으로 바꾸기
- 하이라이트와 타임라인을 더 유기적으로 연결하기

## 관련 커밋

- `4c849f0` `feat: add owner-authenticated v2 analytics pipeline`
- `f68c571` `feat: redesign frontend as owner dashboard`
- `ddd1c45` `fix: stabilize owner-authenticated dashboard runtime`
- `12628bb` `feat: persist auth sessions and handle session expiry`
- `4a91cf6` `feat: improve owner dashboard auth flow and vod analysis ux`
- `c1441b1` `feat: redesign vod highlight timeline board`
- `6e24194` `feat: stabilize vod analysis completion flow`
- `168e788` `chore: manage core-api schema with flyway`
