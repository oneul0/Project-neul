package com.gak.core_api.domain.chat.service;

import com.gak.common.auth.OwnerTokenCodec;
import com.gak.core_api.config.OwnerAccessFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OwnerIdentityResolver 단위 테스트
 *
 * 보안 강화(security-ux 브랜치) 이후 동작 기준:
 *   - GAK_OWNER_ASSERTION 쿠키(HMAC-SHA256 서명)만 신뢰
 *   - X-Chzzk-Owner-Id 헤더, ?ownerId 쿼리파라미터는 완전히 무시
 *   - OwnerAccessFilter가 검증 후 교환 속성에 주입한 ownerId는 그대로 반환
 *
 * 헤더/쿼리 폴백이 존재했을 때 발생했던 취약점:
 *   공격자가 임의 X-Chzzk-Owner-Id 헤더를 추가하면 피해자 채널에 접근 가능했음 (IDOR)
 */
@DisplayName("OwnerIdentityResolver 단위 테스트")
class OwnerIdentityResolverTest {

    private static final String SECRET = "test-owner-secret";
    private static final String SESSION_ID = "test-session-id";

    private OwnerIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OwnerIdentityResolver();
        ReflectionTestUtils.setField(resolver, "ownerTokenSecret", SECRET);
    }

    // ─── 쿠키 기반 정상 경로 ─────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 쿠키 → ownerId 반환")
    void validCookie_returnsOwnerId() {
        String token = OwnerTokenCodec.createToken("owner-123", SESSION_ID, Instant.now().plusSeconds(3600), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isEqualTo("owner-123");
    }

    @Test
    @DisplayName("만료된 쿠키 → null 반환 (헤더 폴백 없음)")
    void expiredCookie_returnsNull() {
        String token = OwnerTokenCodec.createToken("owner-expired", SESSION_ID, Instant.now().minusSeconds(1), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                        .header("X-Chzzk-Owner-Id", "attacker-channel")  // 폴백 없음, 무시됨
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    @Test
    @DisplayName("변조된 쿠키 → null 반환 (헤더 폴백 없음)")
    void tamperedCookie_returnsNull() {
        String token = OwnerTokenCodec.createToken("owner-legit", SESSION_ID, Instant.now().plusSeconds(3600), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token + "tampered"))
                        .header("X-Chzzk-Owner-Id", "attacker-channel")  // 폴백 없음, 무시됨
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    @Test
    @DisplayName("쿠키 없이 아무것도 없을 때 → null 반환")
    void noIdentity_returnsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    // ─── IDOR 방어 검증 ───────────────────────────────────────────────────────
    //
    // 보안 강화 이전에는 아래 두 테스트가 헤더/쿼리 값을 "정상"으로 반환했다.
    // 공격자가 임의 channelId를 설정할 수 있어 IDOR로 이어졌다.
    // 보안 강화 이후 두 경로 모두 null을 반환한다.

    @Test
    @DisplayName("[IDOR 방어] 헤더만 있고 쿠키 없을 때 → null 반환")
    void headerOnly_returnsNull() {
        // 구버전: assertThat(resolver.resolveOwnerId(request)).isEqualTo("header-only-owner")
        // → 공격자가 X-Chzzk-Owner-Id: victim_channel 을 주입하면 피해자 데이터에 접근 가능
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .header("X-Chzzk-Owner-Id", "attacker-controlled-channel")
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    @Test
    @DisplayName("[IDOR 방어] 쿼리 파라미터만 있고 쿠키 없을 때 → null 반환")
    void queryParamOnly_returnsNull() {
        // 구버전: assertThat(resolver.resolveOwnerId(request)).isEqualTo("query-owner")
        // → ?ownerId=victim_channel 로 타인 데이터 접근 가능
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/?ownerId=attacker-controlled-channel").build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    @Test
    @DisplayName("[IDOR 방어] 유효하지 않은 쿠키 + 헤더 → null 반환 (헤더 폴백 없음)")
    void invalidCookieAndHeaderOnly_returnsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/?ownerId=attacker-q")
                        .header("X-Chzzk-Owner-Id", "attacker-h")
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isNull();
    }

    // ─── 쿠키 우선순위 검증 ───────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 쿠키 + 헤더 + 쿼리 → 쿠키 값만 반환 (헤더·쿼리 무시)")
    void validCookieTakesPrecedenceOverHeaderAndQuery() {
        String token = OwnerTokenCodec.createToken("cookie-owner", SESSION_ID, Instant.now().plusSeconds(3600), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/?ownerId=query-owner")
                        .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                        .header("X-Chzzk-Owner-Id", "header-owner")
                        .build()
        );

        assertThat(resolver.resolveOwnerId(exchange)).isEqualTo("cookie-owner");
    }

    // ─── OwnerAccessFilter 캐시 경로 ─────────────────────────────────────────

    @Test
    @DisplayName("OwnerAccessFilter가 교환 속성에 ownerId를 주입한 경우 → 쿠키 재검증 없이 반환")
    void cachedOwnerIdFromFilter_returnsCachedValue() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").build()
        );
        exchange.getAttributes().put(OwnerAccessFilter.ATTR_OWNER_ID, "filter-cached-owner");

        assertThat(resolver.resolveOwnerId(exchange)).isEqualTo("filter-cached-owner");
    }
}
