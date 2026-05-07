package com.gak.collector.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChzzkAuthStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String STATE_KEY_PREFIX = "gak:auth:state:";
    private static final String SESSION_KEY_PREFIX = "gak:auth:session:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<String> issueState() {
        String state = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes());

        return redisTemplate.opsForValue()
                .set(stateKey(state), "1", STATE_TTL)
                .doOnSuccess(ignored -> log.info("[ChzzkAuthStore] Issued auth state"))
                .thenReturn(state);
    }

    public Mono<Boolean> consumeState(String state) {
        if (state == null || state.isBlank()) {
            return Mono.just(false);
        }

        String key = stateKey(state);
        return redisTemplate.hasKey(key)
                .flatMap(exists -> exists
                        ? redisTemplate.delete(key).map(deleted -> deleted > 0)
                        : Mono.just(false))
                .doOnNext(consumed -> log.info("[ChzzkAuthStore] Consumed auth state: {}", consumed))
                .onErrorResume(error -> {
                    log.warn("[ChzzkAuthStore] Failed to consume state: {}", error.getMessage());
                    return Mono.just(false);
                });
    }

    public Mono<ChzzkAuthSession> createSession(ChzzkTokenResponse tokenResponse, ChzzkProfile profile) {
        return writeSession(UUID.randomUUID().toString(), tokenResponse, profile, null);
    }

    public Mono<ChzzkAuthSession> refreshSession(
            String sessionId,
            ChzzkAuthSession currentSession,
            ChzzkTokenResponse tokenResponse,
            ChzzkProfile profile
    ) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.error(new IllegalArgumentException("sessionId must not be blank"));
        }
        return writeSession(sessionId, tokenResponse, profile, currentSession);
    }

    public Mono<ChzzkAuthSession> peekSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.justOrEmpty((ChzzkAuthSession) null);
        }

        return redisTemplate.opsForValue()
                .get(sessionKey(sessionId))
                .flatMap(this::deserializeSession)
                .switchIfEmpty(Mono.empty())
                .onErrorResume(error -> {
                    log.warn("[ChzzkAuthStore] Failed to read session: {}", error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<ChzzkAuthSession> getSession(String sessionId) {
        return peekSession(sessionId)
                .flatMap(session -> {
                    if (session.getExpiresAt().isBefore(Instant.now())) {
                        return invalidateSession(sessionId).then(Mono.empty());
                    }
                    return Mono.just(session);
                });
    }

    public Mono<Void> invalidateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        return redisTemplate.delete(sessionKey(sessionId))
                .doOnSuccess(ignored -> log.info("[ChzzkAuthStore] Invalidated session {}", sessionId))
                .then();
    }

    private Mono<ChzzkAuthSession> writeSession(
            String sessionId,
            ChzzkTokenResponse tokenResponse,
            ChzzkProfile profile,
            ChzzkAuthSession previousSession
    ) {
        Instant expiresAt = Instant.now().plusSeconds(Math.max(tokenResponse.getExpiresIn(), 60));
        ChzzkAuthSession session = ChzzkAuthSession.builder()
                .sessionId(sessionId)
                .accessToken(tokenResponse.getAccessToken())
                .refreshToken(resolveRefreshToken(tokenResponse, previousSession))
                .tokenType(tokenResponse.getTokenType())
                .expiresIn(tokenResponse.getExpiresIn())
                .expiresAt(expiresAt)
                .channelId(profile.getChannelId())
                .channelName(profile.getChannelName())
                .build();

        return serializeSession(session)
                .flatMap(payload -> redisTemplate.opsForValue()
                        .set(sessionKey(sessionId), payload, Duration.ofSeconds(Math.max(tokenResponse.getExpiresIn(), 60)))
                        .thenReturn(session))
                .doOnSuccess(saved -> log.info(
                        "[ChzzkAuthStore] Stored session {} for channelId={} expiresAt={}",
                        saved.getSessionId(),
                        saved.getChannelId(),
                        saved.getExpiresAt()
                ));
    }

    private Mono<String> serializeSession(ChzzkAuthSession session) {
        try {
            return Mono.just(objectMapper.writeValueAsString(session));
        } catch (JsonProcessingException error) {
            return Mono.error(error);
        }
    }

    private Mono<ChzzkAuthSession> deserializeSession(String payload) {
        try {
            return Mono.just(objectMapper.readValue(payload, ChzzkAuthSession.class));
        } catch (JsonProcessingException error) {
            return Mono.error(error);
        }
    }

    private String resolveRefreshToken(ChzzkTokenResponse tokenResponse, ChzzkAuthSession previousSession) {
        if (tokenResponse.getRefreshToken() != null && !tokenResponse.getRefreshToken().isBlank()) {
            return tokenResponse.getRefreshToken();
        }
        return previousSession != null ? previousSession.getRefreshToken() : null;
    }

    private String stateKey(String state) {
        return STATE_KEY_PREFIX + state;
    }

    private String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }
}
