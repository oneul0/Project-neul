package com.gak.collector.auth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChzzkTokenResponse {
    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long expiresIn;
}
