# 24. 토큰 탈취 방어

> 작업일: 2026-05-06  
> 브랜치: `feature/security-ux`

---

## 배경

`GAK_OWNER_ASSERTION`은 HMAC-SHA256 서명 기반의 stateless 토큰입니다.  
기존 구조에서는 토큰이 탈취되면 로그아웃해도 만료 전까지 공격자가 계속 사용 가능했습니다.

또한 쿠키에 `Secure` 플래그가 없어 HTTP 환경에서 네트워크 도청으로 탈취 가능했습니다.

---

## 적용한 방어 2가지

### 1. Secure 플래그

**파일:** `collector/.../ChzzkAuthController.java`

모든 인증 관련 쿠키(`GAK_CHZZK_AUTH_STATE`, `GAK_CHZZK_AUTH_SESSION`, `GAK_OWNER_ASSERTION`)에 `.secure(cookieSecure)` 추가.

- `GAK_COOKIE_SECURE=true` 설정 시 HTTPS에서만 쿠키 전송
- 로컬 개발(`GAK_COOKIE_SECURE=false`)은 HTTP 유지

**프로덕션 `.env`에 반드시 설정:**
```env
GAK_COOKIE_SECURE=true
```

---

### 2. 세션 바인딩 (즉시 revocation)

#### 토큰 구조 변경

**파일:** `common/.../OwnerTokenCodec.java`

```
변경 전: base64url(channelId.expiresAt) + "." + HMAC
변경 후: base64url(channelId.sessionId.expiresAt) + "." + HMAC
```

토큰에 `sessionId`를 포함시켜 서버 Redis 세션과 바인딩합니다.

#### 검증 흐름

```
요청 도착
  │
  ├─ GAK_OWNER_ASSERTION 쿠키 서명 검증 (HMAC-SHA256)
  │     실패 → 401
  │
  ├─ Redis GET gak:owner-session:{ownerId}
  │     키 없음 → 401 (세션 만료 또는 로그아웃)
  │     값 불일치 → 401 (탈취된 토큰 또는 재로그인 후 구 토큰)
  │
  └─ 채널 소유권 확인 (경로의 channelId == ownerId)
        실패 → 403
        성공 → 처리
```

**파일:**
- `core-api/.../OwnerAccessFilter.java` — 위 흐름 전체 담당
- `collector/.../OwnerValidationFilter.java` — subscribe 경로에 동일 적용

#### 로그아웃 시 즉시 차단

```
로그아웃 요청
  → ChzzkSessionRegistry.unregister(channelId)  ← Redis 키 삭제
  → 이후 해당 GAK_OWNER_ASSERTION 토큰은 즉시 401 응답
```

탈취된 토큰이라도 원본 소유자가 로그아웃하면 **즉시** 무효화됩니다.

#### ownerId 전달 방식 개선

`OwnerAccessFilter`가 검증 후 `exchange.getAttributes().put("gak.ownerId", ownerId)`로 주입합니다.  
하위 컨트롤러(`VodController`, `UserVodLibraryController`)는 `OwnerIdentityResolver.resolveOwnerId(exchange)`를 통해 재검증 없이 읽습니다.

---

### 3. 토큰 만료 상한

**파일:** `ChzzkAuthController.java`

`GAK_OWNER_ASSERTION` 쿠키 max-age를 Chzzk 토큰 만료와 무관하게 상한으로 제한합니다.

```java
long maxAge = Math.min(Math.max(session.getExpiresIn(), 60), tokenMaxAgeSeconds);
```

기본값 1시간(`GAK_TOKEN_MAX_AGE_SECONDS=3600`). 탈취 후 세션 바인딩을 우회하는 시나리오에서도 1시간 이내에 만료됩니다.

---

## 환경변수 요약

| 변수 | 기본값 | 설명 |
|---|---|---|
| `GAK_COOKIE_SECURE` | `false` | 프로덕션에서 `true`로 설정 |
| `GAK_TOKEN_MAX_AGE_SECONDS` | `3600` | 토큰 최대 유효 시간(초) |

---

## 방어 후 탈취 시나리오별 대응

| 시나리오 | 방어 결과 |
|---|---|
| XSS로 쿠키 탈취 시도 | `HttpOnly=true` → JS 접근 불가 |
| HTTP 구간 도청 (MITM) | `Secure=true` (프로덕션) → HTTPS만 전송 |
| 쿠키 복사 후 다른 브라우저에서 사용 | 세션 바인딩 → Redis 키 존재 시 동일 sessionId 필요 |
| 로그아웃 후 탈취 토큰 재사용 | Redis 키 삭제 → 즉시 401 |
| 재로그인 후 구 토큰 사용 | 새 sessionId 발급 → 구 토큰 401 |
| 토큰 장기 보관 후 사용 | 만료 상한(1시간) → 자동 만료 |

---

## 한계

- **Redis 장애 시**: `OwnerAccessFilter`가 `switchIfEmpty`로 401을 반환합니다. Redis가 내려가면 모든 인증이 실패합니다. 이는 fail-secure(보안 우선) 설계로 의도된 동작입니다.
- **동시 로그인 불가**: 같은 channelId로 두 번 로그인하면 두 번째 로그인이 첫 번째 세션을 덮어씁니다 (단일 owner 구조에서는 문제 없음).

---

## 관련 문서

- [22_security_hardening.md](22_security_hardening.md) — 인증 우회·내부 API 보호
- [23_production_deploy_checklist.md](23_production_deploy_checklist.md) — 배포 시 환경변수 설정
