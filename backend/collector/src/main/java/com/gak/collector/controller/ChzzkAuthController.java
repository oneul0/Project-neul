package com.gak.collector.controller;

import com.gak.collector.auth.ChzzkAuthService;
import com.gak.collector.auth.ChzzkAuthSession;
import com.gak.collector.auth.ChzzkAuthStore;
import com.gak.collector.auth.ChzzkSessionRegistry;
import com.gak.common.auth.OwnerTokenCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/chzzk")
@RequiredArgsConstructor
public class ChzzkAuthController {

    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
    private static final String AUTH_STATE_COOKIE = "NEUL_CHZZK_AUTH_STATE";
    private static final String AUTH_SESSION_COOKIE = "NEUL_CHZZK_AUTH_SESSION";
    private static final String OWNER_ASSERTION_COOKIE = "GAK_OWNER_ASSERTION";

    private final ChzzkAuthStore authStore;
    private final ChzzkAuthService authService;
    private final ChzzkSessionRegistry sessionRegistry;

    @Value("${gak.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${gak.owner-token-secret:dev-gak-token-secret}")
    private String ownerTokenSecret;

    @Value("${gak.cookie.secure:false}")
    private boolean cookieSecure;

    /** GAK_OWNER_ASSERTION 쿠키 최대 유효 기간 (초). Chzzk 토큰 만료와 무관하게 상한을 둔다. */
    @Value("${gak.token.max-age-seconds:3600}")
    private long tokenMaxAgeSeconds;

    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        return authStore.issueState()
                .map(state -> {
                    final String authorizeUrl;
                    try {
                        authorizeUrl = authService.buildAuthorizeUrl(state);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }

                    ResponseCookie stateCookie = ResponseCookie.from(AUTH_STATE_COOKIE, state)
                            .httpOnly(true)
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(Duration.ofMinutes(10))
                            .build();

                    return redirect(authorizeUrl, stateCookie);
                })
                .onErrorResume(error -> {
                    log.error("[ChzzkAuth] Login bootstrap failed: {}", error.getMessage(), error);
                    return Mono.<ResponseEntity<Void>>just(redirect(
                            frontendUrl + "/?auth=config_missing",
                            clearStateCookie(),
                            clearSessionCookie(),
                            clearOwnerAssertionCookie()
                    ));
                });
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(name = AUTH_STATE_COOKIE, required = false) String stateCookieValue
    ) {
        if (stateCookieValue == null || !stateCookieValue.equals(state)) {
            return Mono.<ResponseEntity<Void>>just(redirect(frontendUrl + "/?auth=state_mismatch", clearStateCookie(), clearSessionCookie(), clearOwnerAssertionCookie()));
        }

        return authStore.consumeState(state)
                .flatMap(isValid -> {
                    if (!isValid) {
                        return Mono.<ResponseEntity<Void>>just(redirect(frontendUrl + "/?auth=state_mismatch", clearStateCookie(), clearSessionCookie(), clearOwnerAssertionCookie()));
                    }

                    return authService.exchangeCode(code, state)
                            .flatMap(tokenResponse -> authService.fetchProfile(tokenResponse.getAccessToken())
                                    .flatMap(profile -> authStore.createSession(tokenResponse, profile)))
                            .flatMap(session -> sessionRegistry
                                    .register(session.getChannelId(), session.getSessionId(), session.getExpiresIn())
                                    .thenReturn(session))
                            .map(session -> redirect(frontendUrl + "/channels/" + session.getChannelId() + "?auth=success",
                                    clearStateCookie(),
                                    sessionCookie(session),
                                    ownerAssertionCookie(session)));
                })
                .onErrorResume(error -> {
                    log.error("[ChzzkAuth] Callback failed: {}", error.getMessage(), error);
                    return Mono.<ResponseEntity<Void>>just(redirect(frontendUrl + "/?auth=failed", clearStateCookie(), clearSessionCookie(), clearOwnerAssertionCookie()));
                });
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(
            @CookieValue(name = AUTH_SESSION_COOKIE, required = false) String sessionId
    ) {
        return authStore.peekSession(sessionId)
                .flatMap(session -> {
                    if (shouldRefresh(session)) {
                        return refreshSessionResponse(sessionId, session)
                                .onErrorResume(error -> {
                                    log.warn("[ChzzkAuth] Session refresh failed for channelId={}: {}", session.getChannelId(), error.getMessage());
                                    if (session.getExpiresAt().isAfter(Instant.now())) {
                                        return Mono.just(okMe(session, false));
                                    }
                                    return authStore.invalidateSession(sessionId).then(unauthorizedMe());
                                });
                    }

                    if (session.getExpiresAt().isBefore(Instant.now())) {
                        return authStore.invalidateSession(sessionId).then(unauthorizedMe());
                    }

                    return Mono.just(okMe(session, false));
                })
                .switchIfEmpty(unauthorizedMe());
    }

    @DeleteMapping("/logout")
    public Mono<ResponseEntity<Map<String, Object>>> logout(
            @CookieValue(name = AUTH_SESSION_COOKIE, required = false) String sessionId
    ) {
        return authStore.getSession(sessionId)
                .flatMap(session -> authService.revokeToken(session.getAccessToken(), "access_token")
                        .onErrorResume(error -> Mono.empty())
                        .then(sessionRegistry.unregister(session.getChannelId()))
                        .then(authStore.invalidateSession(sessionId)))
                .switchIfEmpty(authStore.invalidateSession(sessionId))
                .then(Mono.fromSupplier(() -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                        .header(HttpHeaders.SET_COOKIE, clearOwnerAssertionCookie().toString())
                        .body(Map.of("ok", true))));
    }

    private ResponseEntity<Void> redirect(String location, ResponseCookie... cookies) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, location);
        for (ResponseCookie cookie : cookies) {
            headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseCookie sessionCookie(ChzzkAuthSession session) {
        return ResponseCookie.from(AUTH_SESSION_COOKIE, session.getSessionId())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(Math.max(session.getExpiresIn(), 60)))
                .build();
    }

    private boolean shouldRefresh(ChzzkAuthSession session) {
        if (session == null || session.getRefreshToken() == null || session.getRefreshToken().isBlank()) {
            return false;
        }
        return session.getExpiresAt().minus(REFRESH_SKEW).isBefore(Instant.now());
    }

    private Mono<ResponseEntity<Map<String, Object>>> refreshSessionResponse(String sessionId, ChzzkAuthSession currentSession) {
        log.info("[ChzzkAuth] Refreshing session {} for channelId={}", sessionId, currentSession.getChannelId());
        return authService.refreshToken(currentSession.getRefreshToken())
                .flatMap(tokenResponse -> authService.fetchProfile(tokenResponse.getAccessToken())
                        .flatMap(profile -> authStore.refreshSession(sessionId, currentSession, tokenResponse, profile)))
                .flatMap(session -> sessionRegistry
                        .register(session.getChannelId(), session.getSessionId(), session.getExpiresIn())
                        .thenReturn(session))
                .map(session -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, sessionCookie(session).toString())
                        .header(HttpHeaders.SET_COOKIE, ownerAssertionCookie(session).toString())
                        .body(meBody(session, true)))
                .doOnNext(response -> log.info("[ChzzkAuth] Session refresh completed for channelId={}", currentSession.getChannelId()));
    }

    private ResponseEntity<Map<String, Object>> okMe(ChzzkAuthSession session, boolean refreshed) {
        return ResponseEntity.ok(meBody(session, refreshed));
    }

    private Mono<ResponseEntity<Map<String, Object>>> unauthorizedMe() {
        log.info("[ChzzkAuth] No valid CHZZK session found");
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                .header(HttpHeaders.SET_COOKIE, clearOwnerAssertionCookie().toString())
                .body(Map.of(
                        "authenticated", false,
                        "message", "CHZZK login is required."
                )));
    }

    private Map<String, Object> meBody(ChzzkAuthSession session, boolean refreshed) {
        return Map.of(
                "authenticated", true,
                "channelId", session.getChannelId(),
                "channelName", session.getChannelName(),
                "expiresAt", session.getExpiresAt().toString(),
                "refreshed", refreshed
        );
    }

    private ResponseCookie clearStateCookie() {
        return ResponseCookie.from(AUTH_STATE_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(AUTH_SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie ownerAssertionCookie(ChzzkAuthSession session) {
        long maxAge = Math.min(Math.max(session.getExpiresIn(), 60), tokenMaxAgeSeconds);
        String token = OwnerTokenCodec.createToken(
                session.getChannelId(), session.getSessionId(), session.getExpiresAt(), ownerTokenSecret);
        return ResponseCookie.from(OWNER_ASSERTION_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAge))
                .build();
    }

    private ResponseCookie clearOwnerAssertionCookie() {
        return ResponseCookie.from(OWNER_ASSERTION_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
