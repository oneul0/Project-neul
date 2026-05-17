# 22. 보안 강화 작업 기록

> 작업일: 2026-05-06 / 브랜치: `feature/security-ux`  
> 관련 문서: [13_owner_auth_revision.md](13_owner_auth_revision.md), [24_session_theft_defense.md](24_session_theft_defense.md)

---

## 발견된 취약점 요약

| 등급 | 위치 | 위협 |
|------|------|------|
| **Critical** | `OwnerIdentityResolver` | 헤더·쿼리파라미터 폴백으로 ownerId 위조 가능 (IDOR) |
| **Critical** | `OwnerAccessFilter` | `/api/v1/vod/**`, `/api/v1/me/**` 필터 미적용 → 비인증 분석 트리거 |
| **High** | `RagController` | `/internal/rag/**` 무인증 노출 → 마이크로서비스 내부 API 외부 접근 가능 |
| **Medium** | `WebConfig` | CORS allowed-origin 하드코딩 → 환경별 관리 불가 |

---

## 수정 1. IDOR 제거 — OwnerIdentityResolver 헤더/쿼리 폴백 제거

### 배경

`OwnerAccessFilter`가 쿠키로 1차 인증을 해도, `OwnerIdentityResolver`가 컨트롤러에 `ownerId`를 전달할 때 헤더나 쿼리파라미터로 폴백했다.

### 문제

```
공격자가 HTTP 요청에 X-Chzzk-Owner-Id: victim_channel_id 헤더를 추가
→ 쿠키 인증은 통과(공격자 본인 쿠키)
→ ownerId는 victim_channel_id로 설정됨
→ 피해자의 VOD 라이브러리·선호 프로필·시청 기록 접근 가능
```

### 결정 및 근거

쿠키(`GAK_OWNER_ASSERTION`) 외 모든 폴백 제거. 쿠키는 `HttpOnly` 설정으로 JS 접근이 불가하고, HMAC 서명으로 위조가 불가하다. 헤더·쿼리파라미터는 공격자가 임의로 설정할 수 있어 신뢰할 수 없다.

```java
// OwnerIdentityResolver.java

// BEFORE — 폴백 체인이 IDOR 허용
String ownerId = parseCookie(request);
if (ownerId == null)
    ownerId = request.getHeaders().getFirst("X-Chzzk-Owner-Id");  // 위조 가능
if (ownerId == null)
    ownerId = request.getQueryParams().getFirst("ownerId");         // 위조 가능

// AFTER — 쿠키만 허용
public String resolveOwnerId(ServerHttpRequest request) {
    if (!request.getCookies().containsKey("GAK_OWNER_ASSERTION")) return null;
    return parseAndVerifyCookie(request);
}
```

**영향 클래스**:
```
core-api/domain/chat/service/OwnerIdentityResolver.java  ← 폴백 제거
```

---

## 수정 2. 보호 경로 누락 추가 — OwnerAccessFilter

### 배경

`OwnerAccessFilter.isProtectedPath()`에 `/api/v1/vod/**`, `/api/v1/me/**`가 없었다.

### 문제

```
비인증 상태에서 POST /api/v1/vod/{videoNo}/analyze 호출
→ 필터를 완전히 우회
→ 누구나 임의 VOD 분석 트리거 가능 → Ollama LLM 큐 소진
```

### 결정 및 근거

두 경로를 `isProtectedPath()`에 추가. `vod/**`와 `me/**`는 channelId가 URL에 없는 경우라 소유권 비교 없이 인증(쿠키 유효성)만 검증한다.

```java
// OwnerAccessFilter.java

// BEFORE
return path.startsWith("/api/v1/stream/")
        || path.startsWith("/api/v1/poll/")
        || path.startsWith("/api/v1/donations/")
        || path.startsWith("/api/v1/roulette/");

// AFTER
return path.startsWith("/api/v1/stream/")
        || path.startsWith("/api/v1/poll/")
        || path.startsWith("/api/v1/donations/")
        || path.startsWith("/api/v1/roulette/")
        || path.startsWith("/api/v1/vod/")    // 추가
        || path.startsWith("/api/v1/me/")     // 추가
        || path.startsWith("/api/v2/stream/")
        || path.startsWith("/api/v2/state/");
```

**영향 클래스**:
```
core-api/config/OwnerAccessFilter.java  ← isProtectedPath() 수정
```

---

## 수정 3. 내부 API 보호 — InternalAccessFilter 신규 생성

### 배경

`/internal/rag/few-shot`은 `VodHighlightAnalyzer`(analyzer 서비스)가 VOD 하이라이트 LLM 리뷰 시 few-shot 예시를 가져오기 위해 호출하는 마이크로서비스 간 전용 엔드포인트다.

### 문제

```
외부에서 POST /internal/rag/few-shot 직접 호출 가능
→ RAG 임베딩 검색 데이터 무단 접근
→ 악의적인 few-shot 데이터 주입 가능성
```

### 결정 및 근거

**사전 공유 시크릿(`X-Internal-Secret` 헤더)** 방식 채택. 네트워크 격리(Docker internal network)가 더 강력하지만, 현재 로컬 개발 환경에서는 포트가 모두 노출되어 있다. 코드 레벨 방어를 먼저 적용하고, 프로덕션에서 네트워크 격리를 추가한다.

불일치 시 **404** 반환: 경로 존재 자체를 외부에 노출하지 않음(403이면 경로 존재가 드러남).

```java
// InternalAccessFilter.java — @Order(-10)으로 다른 필터보다 먼저 실행
String secret = exchange.getRequest().getHeaders().getFirst("X-Internal-Secret");
if (internalApiSecret.equals(secret)) {
    return chain.filter(exchange);
}
return respondNotFound(exchange);  // 404: 경로 존재 숨김
```

```java
// OllamaAnalyzerService.java — 호출 측에서 시크릿 주입
return webClient.post()
    .uri(coreApiBaseUrl + "/internal/rag/few-shot?k=3")
    .header("X-Internal-Secret", internalApiSecret)  // 추가
    .bodyValue(body)
    ...
```

**영향 클래스**:
```
core-api/config/InternalAccessFilter.java          ← 신규 생성
analyzer/service/OllamaAnalyzerService.java        ← X-Internal-Secret 헤더 주입
core-api/resources/application.yaml               ← gak.internal-api-secret 설정
```

**환경변수**: `GAK_INTERNAL_API_SECRET` (기본 `dev-internal-secret` — 프로덕션에서 교체 필수)

---

## 수정 4. CORS 환경변수화

```java
// WebConfig.java

// BEFORE
registry.addMapping("/**").allowedOrigins("http://localhost:3000");

// AFTER
@Value("${gak.cors.allowed-origins:http://localhost:3000}")
private String[] allowedOrigins;
```

**환경변수**: `GAK_CORS_ALLOWED_ORIGINS=https://your-domain.com` (콤마 구분 다중 도메인 지원)

**영향 클래스**:
```
core-api/config/WebConfig.java  ← @Value 적용
```

---

## 수정 후 보안 경계 흐름

```
외부 요청
    │
    ├─ /internal/**
    │    └─ InternalAccessFilter (@Order -10)
    │         ├─ X-Internal-Secret 일치 → 통과
    │         └─ 불일치 → 404 (경로 존재 숨김)
    │
    ├─ /api/v1/stream/*, /api/v1/poll/*, /api/v1/donations/*,
    │  /api/v1/roulette/*, /api/v1/vod/*, /api/v1/me/*,
    │  /api/v2/stream/*, /api/v2/state/*
    │    └─ OwnerAccessFilter
    │         ├─ GAK_OWNER_ASSERTION 쿠키 HMAC 검증 → 실패 시 401
    │         ├─ Redis gak:owner-session:{ownerId} 확인 → 없으면 401
    │         └─ URL channelId == ownerId 확인 → 불일치 시 403
    │
    └─ /api/v1/lives/*, 공개 GET 엔드포인트
         └─ 인증 불필요
```

---

## 프로덕션 필수 환경변수

```env
GAK_OWNER_TOKEN_SECRET=<강한 랜덤 시크릿>   # HMAC 서명 키
GAK_INTERNAL_API_SECRET=<강한 랜덤 시크릿>  # 마이크로서비스 간 시크릿
GAK_CORS_ALLOWED_ORIGINS=https://your-domain.com
GAK_COOKIE_SECURE=true                       # HTTPS 전용 쿠키
```

미설정 시 기동 시 WARN 로그 출력:
```
[Security] gak.internal-api-secret is using the insecure default value.
```

---

## 향후 고려사항

- **네트워크 격리**: Docker Compose에서 `analyzer`, `collector`를 내부 네트워크에만 두면 `InternalAccessFilter` 없이도 물리적으로 격리 가능.
- **`/api/v1/lives` 감정 데이터 노출**: 전체 라이브 채널 목록과 감정 점수가 공개된다. 현재는 공개 탐색 용도로 의도된 것이나, 감정 데이터 노출 범위 재검토 필요.
