# Project Gak (각) 실행 가이드

최종 업데이트: 2026-05-16

이 문서는 현재 코드 기준 실행 순서를 정리한 문서입니다.
예전 문서에 있던 `schema.sql` 수동 반영, 공개 대시보드 전제, 오래된 배치 설명은 모두 제외했습니다.

## 1. 서비스 구성

- `frontend` : Next.js, 3000
- `collector` : CHZZK 로그인/실시간 채팅/VOD 크롤링, 8081
- `analyzer` : 채팅 분석 및 VOD 편집 후보 계산, 8082
- `core-api` : 저장/조회/SSE, 8083
- `postgres` : 분석 결과 저장
- `redis` : 세션 및 일부 상태 저장
- `kafka` : 서비스 간 이벤트 전달

## 2. 사전 준비

확인 항목:

- Docker Desktop 실행 중
- JDK 17 사용 가능
  - macOS의 `backend/gradlew`는 설치된 JDK 17이 있으면 그 경로를 우선 사용합니다.
  - Windows는 필요하면 `JAVA17_HOME` 또는 `JAVA_HOME`을 JDK 17로 맞춘 뒤 `gradlew.bat`를 실행합니다.
- Node.js / npm 사용 가능
- `backend/.env`에 CHZZK 관련 값 존재

선택 오버라이드:

- 로컬 `5432`가 이미 다른 PostgreSQL에 사용 중이면 `backend/.env`에 아래 값을 추가합니다.

```env
GAK_POSTGRES_HOST_PORT=55432
```

- `core-api`는 `backend/.env`의 `GAK_POSTGRES_HOST`, `GAK_POSTGRES_HOST_PORT`, `GAK_POSTGRES_DB`, `GAK_POSTGRES_APP_USER`, `GAK_POSTGRES_APP_PASSWORD`를 읽습니다.

## 3. 권장 실행 순서

### 3-1. 인프라 실행

```powershell
cd backend
docker compose up -d
```

확인:

```powershell
docker ps
```

### 3-2. core-api 실행

중요:

- core-api 시작 시 Flyway가 DB 스키마를 최신 상태로 맞춥니다.
- `backend/.env`에 `GAK_POSTGRES_HOST_PORT`를 넣었다면 같은 값으로 Docker 포트와 core-api 연결이 함께 바뀝니다.
- `8083` 포트가 이미 다른 프로세스에 사용 중이면 core-api가 시작되지 않습니다. 이 경우 먼저 해당 프로세스를 종료한 뒤 다시 실행합니다.

```powershell
cd backend
.\gradlew.bat :core-api:bootRun
```

### 3-3. analyzer 실행

```powershell
cd backend
.\gradlew.bat :analyzer:bootRun
```

### 3-4. collector 실행

```powershell
cd backend
.\gradlew.bat :collector:bootRun
```

### 3-5. frontend 실행

```powershell
cd frontend
npm run dev
```

## 4. 로그인 및 owner 대시보드 진입

현재 구조는 공개 탐색형이 아니라 owner 전용 대시보드 기준입니다.

흐름:

1. 브라우저에서 frontend 접속
2. 로그인 버튼 클릭
3. Next API proxy를 통해 collector의 CHZZK 로그인 시작
4. callback 후 Redis 세션/owner assertion 쿠키 발급
5. `/api/chzzk/me`로 로그인 상태 확인
6. 본인 채널 대시보드 진입

## 5. 라이브 채팅 분석 테스트

실제 방송 없이 테스트하려면 mock chat 주입 API를 사용할 수 있습니다.

```powershell
curl.exe -X POST "http://localhost:8081/api/v1/dev/mock-chat/채널ID?count=10"
```

## 6. VOD 분석 테스트

현재 UX는 다음과 같습니다.

1. VOD 번호 또는 전체 URL 입력
2. `조회`
3. 메타데이터 카드 확인
4. `분석 시작`
5. 상태가 `요청 접수 -> 채팅 수집 중 -> 하이라이트 계산 중 -> 완료됨`으로 진행
6. 완료 후 타임라인과 하이라이트 카드 확인

## 7. 상태가 이상할 때 바로 볼 것

- `collector`에서 `VOD-Crawler` 로그
- `analyzer`에서 finalize 로그
- `core-api`에서 Flyway 및 timeline/highlight consumer 로그

## 8. 자주 하는 실수

- 로컬 PostgreSQL이 5432를 잡고 있어 Docker DB 대신 그쪽으로 붙는 경우
- 위 상황이면 `backend/.env`에 `GAK_POSTGRES_HOST_PORT=55432`처럼 별도 포트를 지정하고 `docker compose up -d`부터 다시 실행합니다.
- 로컬에서 다른 서버가 8083을 사용 중이라 core-api가 `Port 8083 was already in use`로 종료되는 경우
- core-api보다 먼저 collector를 띄워 상태 조회가 꼬이는 경우
- analyzer가 늦게 떠서 completion 이벤트를 놓치는 경우
- 브라우저가 backend를 직접 치는 구조라고 가정하고 디버깅하는 경우

## 9. 중지

개별 서비스는 `Ctrl + C`로 내립니다.

인프라는:

```powershell
cd backend
docker compose down
```

데이터까지 초기화하려면:

```powershell
cd backend
docker compose down -v
```
