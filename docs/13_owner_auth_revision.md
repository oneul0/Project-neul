# 13. Owner 인증 구조 전환 기록

> 작성일: 2026-03-30 / 최종 업데이트: 2026-04-01  
> 관련 문서: [22_security_hardening.md](22_security_hardening.md), [24_session_theft_defense.md](24_session_theft_defense.md)

---

## 배경

초기 설계는 "누구나 채널 ID를 알면 대시보드에 들어올 수 있는" 공개 탐색형 구조였다. 그러나 실제 사용 대상이 스트리머 본인으로 좁혀지면서 접근 제어 모델을 완전히 교체해야 했다.

**전환 전제**: "채널 ID 아는 사람 = 접근 허용" → "로그인한 스트리머 본인 = 자신의 채널에만 접근 허용"

---

## 전환 전 구조 (공개 탐색형)

```
Browser → GET /channels/{channelId}/... → core-api
                                            ↑
                              인증 없음, channelId만 있으면 통과
```

- 누구나 임의 `channelId`로 채팅 분석 결과·하이라이트·VOD 조회 가능
- collector 구독(`POST /subscribe`)도 인증 없이 호출 가능
- `ownerId`를 헤더·쿼리파라미터로 전달하면 그대로 신뢰

---

## 전환 후 구조 (Owner 전용 대시보드)

```
Browser
  └─ Next.js API Proxy
       ├─ GET /api/chzzk/login  →  collector  →  Chzzk OAuth 시작
       └─ /api/v1/**            →  core-api   →  OwnerAccessFilter 통과 필요

OwnerAccessFilter (core-api):
  1. GAK_OWNER_ASSERTION 쿠키 HMAC-SHA256 검증  →  실패 시 401
  2. Redis gak:owner-session:{ownerId} 존재 확인  →  없으면 401
  3. URL의 channelId == ownerId                  →  불일치 시 403
```

---

## 의사결정: 왜 쿠키 + Redis 이중 구조인가

### 문제

HMAC-SHA256 서명만으로는 로그아웃 후에도 탈취된 토큰이 유효하다. stateless 토큰의 revocation 문제다.

### 고려한 선택지

| 방식 | 설명 | 탈취 후 revocation |
|------|------|-------------------|
| JWT(stateless) 단독 | 만료 전까지 유효 | 불가 |
| DB 세션 | 서버 상태 저장 | 가능하지만 요청마다 DB 조회 |
| **HMAC 쿠키 + Redis 세션 바인딩** | 서명 검증(빠름) + 세션 확인(즉시 revocation) | **즉시 가능** |

### 결정

**HMAC 쿠키 + Redis 세션 바인딩** 채택. 서명 검증은 O(1)이고, Redis 조회 한 번으로 로그아웃 즉시 차단 가능하다.

---

## 구현 포인트별 영향 클래스

### 로그인 흐름 (collector)

```java
// ChzzkAuthController.java — OAuth 콜백에서 쿠키 발급
ResponseCookie cookie = ResponseCookie.from("GAK_OWNER_ASSERTION", token)
    .httpOnly(true)
    .secure(cookieSecure)   // prod: true, dev: false
    .path("/")
    .maxAge(tokenMaxAge)
    .build();

// ChzzkSessionRegistry.java — Redis에 세션 저장
redisTemplate.opsForValue()
    .set("gak:owner-session:" + channelId, sessionId, Duration.ofSeconds(tokenMaxAge));
```

영향 파일:
```
collector/controller/ChzzkAuthController.java    ← 쿠키 발급
collector/auth/ChzzkSessionRegistry.java         ← Redis 세션 등록/삭제
collector/auth/ChzzkAuthService.java             ← 토큰 생성 로직
```

### 요청 검증 (core-api)

```java
// OwnerAccessFilter.java — 3단계 검증
String ownerId = ownerIdentityResolver.resolveOwnerId(exchange.getRequest()); // 쿠키 파싱
if (ownerId == null) return unauthorized(exchange);                            // 1단계

return redisTemplate.opsForValue()
    .get("gak:owner-session:" + ownerId)
    .switchIfEmpty(Mono.error(new UnauthorizedException()))                    // 2단계
    .flatMap(sessionId -> {
        String pathChannelId = extractChannelId(exchange.getRequest().getPath());
        if (pathChannelId != null && !pathChannelId.equals(ownerId))
            return forbidden(exchange);                                        // 3단계
        return chain.filter(exchange);
    });
```

영향 파일:
```
core-api/config/OwnerAccessFilter.java               ← 3단계 검증
core-api/domain/chat/service/OwnerIdentityResolver.java ← 쿠키에서 ownerId 추출
```

### 브라우저 직접 접근 차단

브라우저가 `8081`(collector), `8083`(core-api)을 직접 치면 `CORS preflight`에서 차단되거나 filter가 동작하지 않아 우회 가능성이 있었다.

**해결**: 모든 요청을 Next.js API Proxy(`/api/chzzk/*`, `/api/v1/*`)를 통해서만 허용. 브라우저에서 백엔드 포트는 직접 접근 불가.

```
frontend/src/app/api/chzzk/*/route.ts   ← collector 프록시
frontend/src/app/api/channels/*/route.ts ← core-api 프록시
```

---

## 전환 후 보안 경계 요약

| 위협 | 이전 | 이후 |
|------|------|------|
| 임의 channelId로 타인 데이터 조회 | 가능 | OwnerAccessFilter 3단계로 차단 |
| 헤더 조작으로 ownerId 위조 | 가능 (헤더 폴백 존재) | 쿠키만 허용, 폴백 제거 |
| 로그아웃 후 토큰 재사용 | 가능 (stateless) | Redis 키 삭제로 즉시 401 |
| 백엔드 포트 직접 접근 | 가능 | Next.js proxy 강제 |

---

## 현재 알려진 한계

- **동시 로그인**: 같은 channelId로 재로그인하면 이전 세션이 덮어씌워진다. 단일 owner 구조에서는 문제 없지만, 팀 운영 시나리오에서는 별도 고려 필요.
- **Redis 장애**: 인증이 모두 401로 처리된다(fail-secure). Redis 다운 = 서비스 전체 로그인 불가. 의도된 설계다.
- **Chzzk 토큰 갱신**: collector의 OAuth 토큰 만료 처리는 `resilience4j.retry`(max-attempts=5, 지수 백오프)로 재시도하지만, 갱신 실패 시 수동 재로그인이 필요하다.
