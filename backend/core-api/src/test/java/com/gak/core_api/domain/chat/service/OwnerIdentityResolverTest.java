package com.gak.core_api.domain.chat.service;

import com.gak.common.auth.OwnerTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OwnerIdentityResolver 단위 테스트")
class OwnerIdentityResolverTest {

    private static final String SECRET = "test-owner-secret";

    private OwnerIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OwnerIdentityResolver();
        ReflectionTestUtils.setField(resolver, "ownerTokenSecret", SECRET);
    }

    @Test
    @DisplayName("유효한 쿠키 → ownerId 반환")
    void validCookie_returnsOwnerId() {
        String token = OwnerTokenCodec.createToken("owner-123", Instant.now().plusSeconds(3600), SECRET);
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("owner-123");
    }

    @Test
    @DisplayName("만료된 쿠키 → 헤더 폴백")
    void expiredCookie_fallsBackToHeader() {
        String token = OwnerTokenCodec.createToken("owner-expired", Instant.now().minusSeconds(1), SECRET);
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                .header("X-Chzzk-Owner-Id", "header-owner")
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("header-owner");
    }

    @Test
    @DisplayName("변조된 쿠키 → 헤더 폴백")
    void tamperedCookie_fallsBackToHeader() {
        String token = OwnerTokenCodec.createToken("owner-legit", Instant.now().plusSeconds(3600), SECRET);
        String tampered = token + "X";
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .cookie(new HttpCookie("GAK_OWNER_ASSERTION", tampered))
                .header("X-Chzzk-Owner-Id", "header-owner")
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("header-owner");
    }

    @Test
    @DisplayName("헤더만 있을 때 → 헤더 값 반환")
    void headerOnly_returnsHeaderValue() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .header("X-Chzzk-Owner-Id", "header-only-owner")
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("header-only-owner");
    }

    @Test
    @DisplayName("쿼리 파라미터만 있을 때 → 쿼리 값 반환")
    void queryParamOnly_returnsQueryValue() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/?ownerId=query-owner").build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("query-owner");
    }

    @Test
    @DisplayName("아무것도 없을 때 → null 반환")
    void noIdentity_returnsNull() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/").build();

        assertThat(resolver.resolveOwnerId(request)).isNull();
    }

    @Test
    @DisplayName("쿠키 > 헤더 > 쿼리파라미터 우선순위 확인")
    void priority_cookieBeatsHeaderBeatsQuery() {
        String token = OwnerTokenCodec.createToken("cookie-owner", Instant.now().plusSeconds(3600), SECRET);
        MockServerHttpRequest request = MockServerHttpRequest.get("/?ownerId=query-owner")
                .cookie(new HttpCookie("GAK_OWNER_ASSERTION", token))
                .header("X-Chzzk-Owner-Id", "header-owner")
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("cookie-owner");
    }

    @Test
    @DisplayName("쿠키 유효하지 않을 때 헤더가 쿼리보다 우선")
    void priority_headerBeatsQueryWhenCookieInvalid() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/?ownerId=query-owner")
                .header("X-Chzzk-Owner-Id", "header-owner")
                .build();

        assertThat(resolver.resolveOwnerId(request)).isEqualTo("header-owner");
    }
}
