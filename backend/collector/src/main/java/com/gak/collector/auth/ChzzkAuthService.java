package com.gak.collector.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.gak.collector.config.ChzzkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChzzkAuthService {

    private final WebClient.Builder webClientBuilder;
    private final ChzzkProperties chzzkProperties;

    public String buildAuthorizeUrl(String state) {
        validateConfiguration();
        return UriComponentsBuilder.fromHttpUrl(chzzkProperties.getAuthUrl() + "/account-interlock")
                .queryParam("clientId", chzzkProperties.getClientId())
                .queryParam("redirectUri", chzzkProperties.getRedirectUri())
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    public Mono<ChzzkTokenResponse> exchangeCode(String code, String state) {
        validateConfiguration();
        Map<String, String> body = Map.of(
                "grantType", "authorization_code",
                "clientId", chzzkProperties.getClientId(),
                "clientSecret", chzzkProperties.getClientSecret(),
                "code", code,
                "state", state
        );

        return webClientBuilder.build()
                .post()
                .uri(chzzkProperties.getBaseUrl() + "/auth/v1/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toTokenResponse);
    }

    public Mono<ChzzkTokenResponse> refreshToken(String refreshToken) {
        validateConfiguration();
        Map<String, String> body = Map.of(
                "grantType", "refresh_token",
                "refreshToken", refreshToken,
                "clientId", chzzkProperties.getClientId(),
                "clientSecret", chzzkProperties.getClientSecret()
        );

        return webClientBuilder.build()
                .post()
                .uri(chzzkProperties.getBaseUrl() + "/auth/v1/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toTokenResponse);
    }

    public Mono<Void> revokeToken(String token, String tokenTypeHint) {
        validateConfiguration();
        Map<String, String> body = Map.of(
                "clientId", chzzkProperties.getClientId(),
                "clientSecret", chzzkProperties.getClientSecret(),
                "token", token,
                "tokenTypeHint", tokenTypeHint == null || tokenTypeHint.isBlank() ? "access_token" : tokenTypeHint
        );

        return webClientBuilder.build()
                .post()
                .uri(chzzkProperties.getBaseUrl() + "/auth/v1/token/revoke")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<ChzzkProfile> fetchProfile(String accessToken) {
        validateConfiguration();
        return webClientBuilder.build()
                .get()
                .uri(chzzkProperties.getBaseUrl() + "/open/v1/users/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toProfile);
    }

    private ChzzkTokenResponse toTokenResponse(JsonNode node) {
        JsonNode content = node.path("content");
        String accessToken = content.path("accessToken").asText();
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = node.path("accessToken").asText();
        }

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Failed to exchange CHZZK authorization code.");
        }

        return ChzzkTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(firstNonBlank(content.path("refreshToken").asText(), node.path("refreshToken").asText()))
                .tokenType(firstNonBlank(content.path("tokenType").asText(), node.path("tokenType").asText(), "Bearer"))
                .expiresIn(readLong(content.path("expiresIn").asText(), node.path("expiresIn").asText(), "3600"))
                .build();
    }

    private ChzzkProfile toProfile(JsonNode node) {
        JsonNode content = node.path("content");
        String channelId = firstNonBlank(
                node.path("channelId").asText(),
                node.path("content").path("channelId").asText(),
                content.path("channelId").asText(),
                content.path("channel").path("channelId").asText());
        String channelName = firstNonBlank(
                node.path("channelName").asText(),
                node.path("content").path("channelName").asText(),
                content.path("channelName").asText(),
                content.path("channel").path("channelName").asText());

        if (channelId == null || channelId.isBlank()) {
            log.error("[ChzzkAuth] Failed to resolve channelId from response: {}", node);
            throw new IllegalStateException("Failed to fetch CHZZK owner profile.");
        }

        return ChzzkProfile.builder()
                .channelId(channelId)
                .channelName(channelName)
                .build();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private long readLong(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 3600L;
    }

    private void validateConfiguration() {
        if (isBlank(chzzkProperties.getBaseUrl())
                || isBlank(chzzkProperties.getAuthUrl())
                || isBlank(chzzkProperties.getClientId())
                || isBlank(chzzkProperties.getClientSecret())
                || isBlank(chzzkProperties.getRedirectUri())) {
            throw new IllegalStateException("CHZZK OAuth configuration is incomplete.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
