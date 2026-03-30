package com.neul.collector.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChzzkAuthStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final Map<String, Instant> states = new ConcurrentHashMap<>();
    private final Map<String, ChzzkAuthSession> sessions = new ConcurrentHashMap<>();

    public String issueState() {
        String state = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.util.UUID.randomUUID().toString().getBytes());
        states.put(state, Instant.now().plus(STATE_TTL));
        return state;
    }

    public boolean consumeState(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }

        Instant expiresAt = states.remove(state);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public ChzzkAuthSession createSession(ChzzkTokenResponse tokenResponse, ChzzkProfile profile) {
        Instant expiresAt = Instant.now().plusSeconds(Math.max(tokenResponse.getExpiresIn(), 60));
        ChzzkAuthSession session = ChzzkAuthSession.builder()
                .sessionId(java.util.UUID.randomUUID().toString())
                .accessToken(tokenResponse.getAccessToken())
                .refreshToken(tokenResponse.getRefreshToken())
                .tokenType(tokenResponse.getTokenType())
                .expiresIn(tokenResponse.getExpiresIn())
                .expiresAt(expiresAt)
                .channelId(profile.getChannelId())
                .channelName(profile.getChannelName())
                .build();
        sessions.put(session.getSessionId(), session);
        return session;
    }

    public ChzzkAuthSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        ChzzkAuthSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }

        if (session.getExpiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return null;
        }

        return session;
    }

    public void invalidateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessions.remove(sessionId);
    }
}
