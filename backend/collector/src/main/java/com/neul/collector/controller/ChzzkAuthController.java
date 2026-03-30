package com.neul.collector.controller;

import com.neul.collector.auth.ChzzkAuthService;
import com.neul.collector.auth.ChzzkAuthSession;
import com.neul.collector.auth.ChzzkAuthStore;
import com.neul.common.auth.OwnerTokenCodec;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/chzzk")
@RequiredArgsConstructor
public class ChzzkAuthController {

    private static final String AUTH_STATE_COOKIE = "NEUL_CHZZK_AUTH_STATE";
    private static final String AUTH_SESSION_COOKIE = "NEUL_CHZZK_AUTH_SESSION";
    private static final String OWNER_ASSERTION_COOKIE = "NEUL_OWNER_ASSERTION";

    private final ChzzkAuthStore authStore;
    private final ChzzkAuthService authService;

    @Value("${neul.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${neul.owner-token-secret:dev-owner-token-secret}")
    private String ownerTokenSecret;

    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        String state = authStore.issueState();
        final String authorizeUrl;

        try {
            authorizeUrl = authService.buildAuthorizeUrl(state);
        } catch (Exception error) {
            log.error("[ChzzkAuth] Login bootstrap failed: {}", error.getMessage(), error);
            return Mono.just(redirect(
                    frontendUrl + "/?auth=config_missing",
                    clearStateCookie(),
                    clearSessionCookie(),
                    clearOwnerAssertionCookie()
            ));
        }

        ResponseCookie stateCookie = ResponseCookie.from(AUTH_STATE_COOKIE, state)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(10))
                .build();

        return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .header(HttpHeaders.LOCATION, authorizeUrl)
                .build());
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(name = AUTH_STATE_COOKIE, required = false) String stateCookieValue
    ) {
        if (stateCookieValue == null || !stateCookieValue.equals(state) || !authStore.consumeState(state)) {
            return Mono.just(redirect(frontendUrl + "/?auth=state_mismatch", clearStateCookie(), clearSessionCookie(), clearOwnerAssertionCookie()));
        }

        return authService.exchangeCode(code, state)
                .flatMap(tokenResponse -> authService.fetchProfile(tokenResponse.getAccessToken())
                        .map(profile -> authStore.createSession(tokenResponse, profile)))
                .map(session -> redirect(frontendUrl + "/channels/" + session.getChannelId() + "?auth=success",
                        clearStateCookie(),
                        sessionCookie(session),
                        ownerAssertionCookie(session)))
                .onErrorResume(error -> {
                    log.error("[ChzzkAuth] Callback failed: {}", error.getMessage(), error);
                    return Mono.just(redirect(frontendUrl + "/?auth=failed", clearStateCookie(), clearSessionCookie(), clearOwnerAssertionCookie()));
                });
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(
            @CookieValue(name = AUTH_SESSION_COOKIE, required = false) String sessionId
    ) {
        ChzzkAuthSession session = authStore.getSession(sessionId);
        if (session == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "authenticated", false,
                            "message", "CHZZK login is required."
                    )));
        }

        return Mono.just(ResponseEntity.ok(Map.of(
                "authenticated", true,
                "channelId", session.getChannelId(),
                "channelName", session.getChannelName(),
                "expiresAt", session.getExpiresAt().toString()
        )));
    }

    @DeleteMapping("/logout")
    public Mono<ResponseEntity<Map<String, Object>>> logout(
            @CookieValue(name = AUTH_SESSION_COOKIE, required = false) String sessionId
    ) {
        ChzzkAuthSession session = authStore.getSession(sessionId);
        Mono<Void> revokeMono = session != null
                ? authService.revokeToken(session.getAccessToken(), "access_token").onErrorResume(error -> Mono.empty())
                : Mono.empty();

        return revokeMono.then(Mono.fromSupplier(() -> {
            authStore.invalidateSession(sessionId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, clearOwnerAssertionCookie().toString())
                    .body(Map.of("ok", true));
        }));
    }

    private ResponseEntity<Void> redirect(String location, ResponseCookie... cookies) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location);
        for (ResponseCookie cookie : cookies) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }

    private ResponseCookie sessionCookie(ChzzkAuthSession session) {
        return ResponseCookie.from(AUTH_SESSION_COOKIE, session.getSessionId())
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(Math.max(session.getExpiresIn(), 60)))
                .build();
    }

    private ResponseCookie clearStateCookie() {
        return ResponseCookie.from(AUTH_STATE_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(AUTH_SESSION_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie ownerAssertionCookie(ChzzkAuthSession session) {
        String token = OwnerTokenCodec.createToken(session.getChannelId(), session.getExpiresAt(), ownerTokenSecret);
        return ResponseCookie.from(OWNER_ASSERTION_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(Math.max(session.getExpiresIn(), 60)))
                .build();
    }

    private ResponseCookie clearOwnerAssertionCookie() {
        return ResponseCookie.from(OWNER_ASSERTION_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
