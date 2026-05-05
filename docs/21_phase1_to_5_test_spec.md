# Phase 1–5 테스트 명세서 및 결과

> **브랜치**: `feature/phase1-dashboard-removal`  
> **작성일**: 2026-05-04  
> **대상 커밋**: `f0543e7` → `a711729` (5 commits, +1050 / -2519 lines)

---

## 개요

| Phase | 제목 | 주요 변경 모듈 |
|-------|------|--------------|
| 1 | 스트리머 대시보드 폐기, 투표+VOD 2탭 구조 재편 | frontend |
| 2 | 도네이션 룰렛 파이프라인 | collector, core-api, frontend |
| 3 | 성인 방송 자동 감지 및 OAuth 폴백 | collector |
| 4 | VOD 하이라이트 고도화 | frontend |
| 5 | 투표 집계 버그 수정 및 UI 완성 | core-api, frontend |

---

## 정적 검증 결과 (자동)

### 백엔드 컴파일

```
./gradlew :common:compileJava
./gradlew :collector:compileJava
./gradlew :analyzer:compileJava
./gradlew :core-api:compileJava
```

| 모듈 | 결과 |
|------|------|
| common | ✅ BUILD SUCCESSFUL |
| collector | ✅ BUILD SUCCESSFUL |
| analyzer | ✅ BUILD SUCCESSFUL |
| core-api | ✅ BUILD SUCCESSFUL |

### 프론트엔드 타입체크

```
npx tsc --noEmit
```

| 결과 | 비고 |
|------|------|
| ✅ 오류 없음 | `e2e/` 테스트 파일의 기존 오류는 이번 변경과 무관 |

### 보안 필터 코드 리뷰 — OwnerAccessFilter

| 검사 항목 | 결과 |
|-----------|------|
| `/api/v1/poll/` prefix 보호 등록 | ✅ `isProtectedPath()` 에 등록됨 |
| `extractRoomId("poll")` 세그먼트 추출 | ✅ `segments[i+1]` 로 정확히 추출 |
| Phase 5 추가 경로 (`/items`, `/voters`, `/voters/{id}/history`) | ✅ 모두 prefix 내에 포함되어 추가 수정 불필요 |
| 도네이션 경로 (`/api/v1/donations/`) | ✅ Phase 2 때 등록됨 |

---

## Phase 1 — 대시보드 재편

### 테스트 케이스

| ID | 항목 | 절차 | 기대 결과 |
|----|------|------|-----------|
| P1-01 | 채널 페이지 진입 | `/channels/{channelId}` 접속 | 투표/VOD 2탭 렌더링 |
| P1-02 | 탭 전환 | 상단 "투표" ↔ "VOD" 버튼 클릭 | 각 탭 컨텐츠 전환, URL 유지 |
| P1-03 | 비로그인 상태 | 쿠키 없이 접속 | "치지직 로그인" 버튼 표시, 수집 버튼 미노출 |
| P1-04 | 타인 채널 접속 | 로그인 후 다른 channelId URL 접속 | 황색 경고("로그인한 계정의 채널과 다릅니다") 표시, 수집 버튼 미노출 |
| P1-05 | 로그인 플로우 | "치지직 로그인" → OAuth → 콜백 | 본인 채널로 리다이렉트, 수집 버튼 노출 |
| P1-06 | 로그아웃 | "로그아웃" 버튼 클릭 | `/` 리다이렉트, 쿠키 삭제 |

### 검증 방법
- 브라우저 수동 테스트 (프론트엔드 로컬 서버 `npm run dev`)
- Network 탭에서 `/api/chzzk/me` 200/401 응답 확인

---

## Phase 2 — 도네이션 룰렛 파이프라인

### 아키텍처 흐름

```
NidChatCollector(cmd=93102) → DonationProducer → donation-events(Kafka)
  → DonationService(@KafkaListener) → Redis RPUSH neul:donations:{channelId}
  → GET /api/v1/donations/{channelId} → frontend useDonationRoulette
```

### 테스트 케이스

| ID | 항목 | 절차 | 기대 결과 |
|----|------|------|-----------|
| P2-01 | DONATION Kafka 라우팅 | Chzzk 방송 중 채팅 도네이션 발생 | `donation-events` 토픽에 메시지 적재 (kafka-console-consumer 확인) |
| P2-02 | Redis 저장 | P2-01 이후 `LRANGE neul:donations:{channelId} 0 -1` | DonationEntry JSON 직렬화 항목 확인 |
| P2-03 | 도네이션 목록 조회 | `GET /api/channels/{channelId}/donations` | 200, DonationEntry 배열 반환 |
| P2-04 | 풀 최대치 | 200개 초과 도네이션 발생 | Redis LTRIM으로 자동 200개 유지 |
| P2-05 | 룰렛 스핀 | "스핀" 버튼 클릭 | POST `/donations/spin` → 랜덤 당첨자 표시 (보라색 winner 배너) |
| P2-06 | 당첨자 없음 | 풀이 비어있을 때 스핀 | 404 응답, UI에 에러 없이 빈 상태 유지 |
| P2-07 | 풀 초기화 | "초기화" 버튼 클릭 | DELETE → 204, 목록 비워짐 |
| P2-08 | 비소유자 접근 | 다른 채널 ID로 접근 시 | OwnerAccessFilter → 403 Forbidden |
| P2-09 | 미로그인 접근 | 쿠키 없이 접근 시 | 401 Unauthorized |
| P2-10 | 5초 폴링 | 소유자 탭 열어둔 상태 | Network 탭에서 5초마다 GET 요청 발생 확인 |

### 검증 방법
- 실제 방송 or Postman으로 `/api/v1/donations/{channelId}` 직접 POST 테스트
- Redis CLI: `LRANGE neul:donations:{channelId} 0 -1`

---

## Phase 3 — 성인 방송 자동 감지

### 분기 흐름

```
getChatChannelId()
  ├─ NID API (polling/v2) → content != null → chatChannelId 반환 ✓
  └─ content == null (성인 방송)
       ├─ ChzzkProperties.clientId 없음 → AdultStreamException(hasCredentials=false)
       │    → 422 adult_stream_api_unconfigured
       └─ clientId 있음
            ├─ SessionRegistry에 channelId 없음 (미로그인)
            │    → AdultStreamException(hasCredentials=true)
            │    → 422 adult_stream_login_required
            └─ OAuth 토큰 있음 → Official API 호출 → chatChannelId 반환 ✓
```

### 테스트 케이스

| ID | 항목 | 전제 조건 | 기대 결과 |
|----|------|----------|-----------|
| P3-01 | 일반 방송 수집 시작 | 일반 방송 채널, 로그인 | 200 `subscribed` |
| P3-02 | 성인 방송 + API 미설정 | `chzzk.client-id` 미설정 | 422 `adult_stream_api_unconfigured`, 프론트 경고 표시 |
| P3-03 | 성인 방송 + API 설정 + 미로그인 | `client-id` 설정, 세션 없음 | 422 `adult_stream_login_required`, 프론트 "치지직 공식 로그인 후 시도" 표시 |
| P3-04 | 성인 방송 + 로그인 완료 | `client-id` 설정, 세션 존재 | Official API 폴백 성공 → 200 `subscribed` |
| P3-05 | 로그인 시 SessionRegistry 등록 | OAuth 콜백 완료 | `neul:owner-session:{channelId}` Redis 키 존재 확인 |
| P3-06 | 로그아웃 시 SessionRegistry 삭제 | 로그아웃 요청 | `neul:owner-session:{channelId}` Redis 키 삭제 확인 |

### 검증 방법
- Redis CLI: `GET neul:owner-session:{channelId}`
- 성인 방송 채널 없는 경우: Mockito로 `NidChatCollector.getChatChannelIdViaNid()` → `Mono.empty()` 강제 반환 테스트

---

## Phase 4 — VOD 하이라이트 고도화

### 테스트 케이스

| ID | 항목 | 절차 | 기대 결과 |
|----|------|------|-----------|
| P4-01 | 시간순 정렬 (기본) | 분석 완료 VOD 열기 | 하이라이트가 startSeconds 오름차순 배열 |
| P4-02 | 점수순 정렬 전환 | "점수순" 버튼 클릭 | highlightScore 내림차순으로 재배열, 필터 유지 |
| P4-03 | 간략 보기 전환 | AlignJustify 아이콘 버튼 클릭 | 카드가 1줄 compact 형태(타임코드 + 장면 + 점수)로 전환 |
| P4-04 | 간략 보기 → 상세 보기 | LayoutList 아이콘 버튼 클릭 | 카드가 전체 정보 표시 형태로 복원 |
| P4-05 | 간략 보기 호버 선택 | compact 카드에 마우스 올리기 | 우측 "선택한 장면 상세" 패널 동기화 |
| P4-06 | 차트 바 클릭 | 타임라인 차트의 특정 바 클릭 | 해당 구간과 가장 가까운 하이라이트로 목록 스크롤 + 상세 패널 동기화 |
| P4-07 | 차트 바 클릭 — 하이라이트 없음 | 하이라이트가 0개인 상태에서 바 클릭 | 오류 없이 아무 동작 안 함 |
| P4-08 | 필터 + 정렬 조합 | "편집점" 필터 + "점수순" 정렬 | 편집점 표시된 항목만 점수 내림차순 표시 |

### 검증 방법
- 분석 완료된 VOD 번호로 워크스페이스 열기 후 브라우저 수동 테스트
- highlights 배열이 없는 빈 상태에서 P4-07 재현

---

## Phase 5 — 투표 집계 버그 수정

### 핵심 버그 요약

| 구분 | 수정 전 | 수정 후 |
|------|---------|---------|
| `getPollResults` 반환값 | `{"1": 3, "2": 5}` | `{"찬성": 3, "반대": 5}` |
| `getVoters` 반환값 | `{senderId: "1"}` | `{닉네임: "찬성"}` |
| PollResults 프로그레스 바 트랙 | `bg-white/6` (거의 투명) | `bg-slate-200` |

### 테스트 케이스

| ID | 항목 | 절차 | 기대 결과 |
|----|------|------|-----------|
| P5-01 | 투표 항목 생성 | 항목 편집에서 "찬성", "반대" 입력 후 저장 | `POST /poll/{id}/items` 200, Redis `poll:{id}:items` 리스트 저장 |
| P5-02 | 투표 명령어 채팅 | 시청자가 `!1` 채팅 | Redis `poll:{id}:votes` 에 `senderId → "1"` 저장 |
| P5-03 | 투표 집계 API | `GET /poll/{channelId}/results` | `{"찬성": N, "반대": M}` 반환 (라벨 키) |
| P5-04 | 프론트 집계 표시 | 투표 탭 관찰 | "찬성" 항목 바에 실제 투표 수 표시 |
| P5-05 | 투표자 목록 API | `GET /poll/{channelId}/voters` | `{닉네임: "찬성"}` 반환 |
| P5-06 | 투표자 목록 UI | 투표 결과 항목 클릭 | "참여 시청자" 섹션에 닉네임 표시 |
| P5-07 | 투표자 히스토리 | 닉네임 버튼 클릭 | 해당 시청자의 채팅 기록 로드 (senderId 또는 sender 폴백) |
| P5-08 | 투표 방법 안내 — 컴포저 | 항목 편집 열기 | 하단에 `!1 (찬성), !2 (반대) 형식으로 입력해 투표합니다` 배너 표시 |
| P5-09 | 투표 대기 상태 힌트 | 항목 설정 후 0표 상태 | `!1, !2 형식으로 입력하면 집계됩니다` 힌트 카드 표시 |
| P5-10 | 투표함 초기화 | "투표 초기화" → "초기화 진행" | `DELETE /poll/{id}` + Redis `votes`, `voter-names` 동시 삭제 |
| P5-11 | 소유자 전용 보호 | 다른 채널 쿠키로 items POST | 403 Forbidden |
| P5-12 | 미로그인 보호 | 쿠키 없이 results GET | 401 Unauthorized |
| P5-13 | 빈 항목 상태 | items 없을 때 투표 집계 | `{}` 빈 맵 반환, UI "먼저 투표 항목을 만들어 주세요" 표시 |
| P5-14 | 범위 초과 투표 | `!99` 채팅 (항목 2개) | `resolveLabel` → null → 집계 제외 |

### 검증 방법

#### Redis 직접 확인
```bash
# 투표 데이터 확인
redis-cli HGETALL poll:{channelId}:votes
redis-cli HGETALL poll:{channelId}:voter-names
redis-cli LRANGE poll:{channelId}:items 0 -1
```

#### API 직접 호출
```bash
# 결과 확인 (라벨 키 반환 여부)
curl -b "NEUL_OWNER_ASSERTION=..." \
  http://localhost:8083/api/v1/poll/{channelId}/results

# 투표자 목록 (닉네임 키 반환 여부)
curl -b "NEUL_OWNER_ASSERTION=..." \
  http://localhost:8083/api/v1/poll/{channelId}/voters
```

---

## 회귀 테스트 항목

기존 기능이 이번 변경으로 깨지지 않았는지 확인할 항목입니다.

| ID | 항목 | 확인 방법 |
|----|------|----------|
| R-01 | VOD 분석 요청 및 상태 폴링 | VOD 번호 조회 → 분석 시작 → 상태 COMPLETED 확인 |
| R-02 | 하이라이트 좋아요/편집점/별로예요 | 각 액션 클릭 → 필터 반영 확인 |
| R-03 | SSE 실시간 스트림 | `/api/v1/stream/{channelId}/sse` 구독 → 채팅 이벤트 수신 |
| R-04 | 채팅 분석 파이프라인 | 일반 CHAT → analyzed-chat-topic → DB 저장 |
| R-05 | OAuth 로그인/콜백/로그아웃 | 전체 플로우 재확인 |
| R-06 | 채팅 수집 시작/중지 | 수집 시작 버튼 → `/subscribe` POST → 수집 중지 |

---

## 병합 준비 체크리스트

- [x] `common`, `collector`, `analyzer`, `core-api` 컴파일 성공
- [x] 프론트엔드 TypeScript 타입 오류 없음
- [x] `OwnerAccessFilter` — Phase 5 신규 경로 보호 확인
- [x] `clearPoll` — `voter-names` 해시 동시 삭제 처리
- [ ] P2~P5 수동 기능 테스트 (서비스 실행 후 확인 필요)
- [ ] R-01 ~ R-06 회귀 테스트 (서비스 실행 후 확인 필요)
