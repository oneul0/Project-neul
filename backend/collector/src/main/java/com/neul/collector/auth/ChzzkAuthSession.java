package com.neul.collector.auth;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class ChzzkAuthSession {
    private final String sessionId;
    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long expiresIn;
    private final Instant expiresAt;
    private final String channelId;
    private final String channelName;
}
