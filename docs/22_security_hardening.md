# 22. 보안 강화 작업 기록

> 작업일: 2026-05-06  
> 브랜치: `feature/security-ux`  
> 중점: 인증 우회 차단, 내부 API 보호, CORS 관리

---

## 발견된 취약점 요약

| 등급 | 위치 | 내용 |
|---|---|---|
| Critical | `OwnerIdentityResolver` | 헤더·쿼리파라미터 폴백으로 ownerId 위조 가능 |
| Critical | `OwnerAccessFilter` | `/api/v1/vod/**`, `/api/v1/me/**` 필터 미적용 |
| High | `RagController` | `/internal/rag/**` 무인증 노출 |
| Medium | `WebConfig` | CORS allowed-origin 하드코딩 |

---

## 수정 내용

### 1. OwnerIdentityResolver — 헤더/쿼리 폴백 제거

**파일:** `backend/core-api/.../service/OwnerIdentityResolver.java`

**문제:** 쿠키 검증 실패 시 `X-Chzzk-Owner-Id` 헤더 → `?ownerId` 쿼리파라미터 순으로 폴백했음.  
누구나 `X-Chzzk-Owner-Id: victim_id` 헤더 하나로 타인의 VOD 라이브러리, 선호도 프로필, 시청 활동 기록에 접근 가능했음.

**수정:** 쿠키(`GAK_OWNER_ASSERTION`) 검증만 남기고 헤더·쿼리 폴백 완전 제거.

```java
// Before
String headerOwnerId = request.getHeaders().getFirst("X-Chzzk-Owner-Id");
if (headerOwnerId != null ...) return headerOwnerId;
String queryOwnerId = request.getQueryParams().getFirst("ownerId");
if (queryOwnerId != null ...) return queryOwnerId;

// After — 쿠키만 허용
public String resolveOwnerId(ServerHttpRequest request) {
    if (request.getCookies().containsKey("GAK_OWNER_ASSERTION")) { ... }
    return null;
}
```

---

### 2. OwnerAccessFilter — 보호 경로 추가

**파일:** `backend/core-api/.../config/OwnerAccessFilter.java`

**문제:** `isProtectedPath()`에 `/api/v1/vod/**`, `/api/v1/me/**`가 없어 필터를 완전히 우회했음.  
`POST /api/v1/vod/{videoNo}/analyze` (VOD 분석 트리거)를 비인증 상태로 호출 가능했음.

**수정:** 두 경로 추가. `extractRoomId()`가 videoNo/me 경로에서 null을 반환하므로 소유권 비교 없이 인증(쿠키 유효성)만 검증함.

```java
return path.startsWith("/api/v1/stream/")
        || path.startsWith("/api/v1/poll/")
        || path.startsWith("/api/v1/donations/")
        || path.startsWith("/api/v1/roulette/")
        || path.startsWith("/api/v1/vod/")    // 추가
        || path.startsWith("/api/v1/me/")     // 추가
        || path.startsWith("/api/v2/stream/")
        || path.startsWith("/api/v2/state/");
```

---

### 3. InternalAccessFilter 신규 생성

**파일:** `backend/core-api/.../config/InternalAccessFilter.java`

**문제:** `/internal/rag/few-shot`은 analyzer → core-api 마이크로서비스 간 전용 엔드포인트임에도 인증 없이 외부에서 직접 호출 가능했음.

**수정:** `X-Internal-Secret` 헤더로 사전 공유 시크릿을 검증하는 필터 추가. 불일치 시 404 반환(경로 존재 자체를 노출하지 않음). `@Order(-10)`으로 다른 필터보다 먼저 실행.

```java
String secret = exchange.getRequest().getHeaders().getFirst("X-Internal-Secret");
if (internalApiSecret.equals(secret)) {
    return chain.filter(exchange);
}
// 경로 존재 노출 방지를 위해 404 반환
return respondNotFound(exchange);
```

**환경변수:** `GAK_INTERNAL_API_SECRET` (기본값 `dev-internal-secret` — 프로덕션에서 반드시 교체)

---

### 4. OllamaAnalyzerService — X-Internal-Secret 헤더 주입

**파일:** `backend/analyzer/.../service/OllamaAnalyzerService.java`

`/internal/rag/few-shot` 호출 시 `X-Internal-Secret` 헤더를 포함하도록 수정. `gak.internal-api-secret` 프로퍼티에서 읽음.

```java
return webClient.post()
        .uri(coreApiBaseUrl + "/internal/rag/few-shot?k=3")
        .header("X-Internal-Secret", internalApiSecret)  // 추가
        .bodyValue(body)
        ...
```

---

### 5. WebConfig — CORS allowed-origins 환경변수화

**파일:** `backend/core-api/.../config/WebConfig.java`

하드코딩된 `http://localhost:3000`을 `@Value`로 대체. 프로덕션 도메인은 `GAK_CORS_ALLOWED_ORIGINS` 환경변수에 콤마 구분으로 지정.

```java
@Value("${gak.cors.allowed-origins:http://localhost:3000}")
private String[] allowedOrigins;
```

---

## 프로덕션 배포 전 필수 환경변수

`.env` 또는 컨테이너 환경변수에 반드시 설정:

```env
GAK_OWNER_TOKEN_SECRET=<강한 랜덤 시크릿>
GAK_INTERNAL_API_SECRET=<강한 랜덤 시크릿>
GAK_CORS_ALLOWED_ORIGINS=https://your-domain.com
```

미설정 시 서비스 기동 시 WARN 로그가 출력됨:
```
[Security] gak.internal-api-secret is using the insecure default value.
```

---

## 보안 모델 정리 (수정 후)

```
외부 요청
    │
    ├─ /internal/**  →  InternalAccessFilter (X-Internal-Secret 검증)
    │                     실패 시 404 반환
    │
    ├─ /api/v1/stream/*, /api/v1/poll/*, /api/v1/donations/*,
    │  /api/v1/roulette/*, /api/v1/vod/*, /api/v1/me/*,
    │  /api/v2/stream/*, /api/v2/state/*
    │       →  OwnerAccessFilter (GAK_OWNER_ASSERTION 쿠키 검증)
    │               + 경로에 채널ID 포함 시 소유권 비교
    │
    └─ /api/v1/roulette/* (GET), /api/v1/lives/*
            →  공개 (인증 불필요)
```

---

## 미적용 사항 (향후 고려)

- **`/api/v1/lives`**: 전체 라이브 채널 목록 + 감정 점수 공개 노출. 현재 공개 탐색 용도이므로 의도적이나, 감정 데이터 노출 범위 재검토 필요.
- **네트워크 격리**: Docker Compose에서 `core-api:8083`만 호스트에 노출하고 `analyzer`, `collector`는 내부 네트워크에만 두면 `InternalAccessFilter`의 시크릿 없이도 물리적으로 격리 가능. 현재는 코드 레벨 방어에 의존.
