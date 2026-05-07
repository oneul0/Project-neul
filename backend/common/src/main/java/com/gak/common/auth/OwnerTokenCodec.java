package com.gak.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

public final class OwnerTokenCodec {

    private OwnerTokenCodec() {
    }

    /** 토큰에서 추출한 소유자 정보. */
    public record OwnerClaims(String ownerId, String sessionId) {}

    /**
     * 토큰 생성. 페이로드 형식: {@code channelId.sessionId.expiresAtEpochSecond}
     * sessionId를 포함해 서버 측 세션과 바인딩할 수 있습니다.
     */
    public static String createToken(String ownerChannelId, String sessionId, Instant expiresAt, String secret) {
        String payload = ownerChannelId + "." + sessionId + "." + expiresAt.getEpochSecond();
        String signature = sign(payload, secret);
        return base64Url(payload) + "." + signature;
    }

    /**
     * 토큰 검증 후 ownerId + sessionId를 반환합니다.
     * 서명 불일치, 만료, 형식 오류 시 null을 반환합니다.
     */
    public static OwnerClaims verifyAndExtractClaims(String token, String secret) {
        if (token == null || token.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        String expectedSignature = sign(payload, secret);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        // 페이로드: channelId.sessionId.expiresAtEpochSecond
        String[] payloadParts = payload.split("\\.", 3);
        if (payloadParts.length != 3) {
            return null;
        }

        try {
            long expiresAt = Long.parseLong(payloadParts[2]);
            if (Instant.now().isAfter(Instant.ofEpochSecond(expiresAt))) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }

        return new OwnerClaims(payloadParts[0], payloadParts[1]);
    }

    /** ownerId만 필요한 경우의 편의 메서드. */
    public static String verifyAndExtractOwner(String token, String secret) {
        OwnerClaims claims = verifyAndExtractClaims(token, secret);
        return claims != null ? claims.ownerId() : null;
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign owner token.", exception);
        }
    }

    private static String base64Url(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
