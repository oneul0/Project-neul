package com.neul.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class OwnerTokenCodec {

    private OwnerTokenCodec() {
    }

    public static String createToken(String ownerChannelId, Instant expiresAt, String secret) {
        String payload = ownerChannelId + "." + expiresAt.getEpochSecond();
        String signature = sign(payload, secret);
        return base64Url(payload) + "." + signature;
    }

    public static String verifyAndExtractOwner(String token, String secret) {
        if (token == null || token.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return null;
        }

        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String expectedSignature = sign(payload, secret);
        if (!expectedSignature.equals(parts[1])) {
            return null;
        }

        String[] payloadParts = payload.split("\\.");
        if (payloadParts.length != 2) {
            return null;
        }

        long expiresAtEpochSecond;
        try {
            expiresAtEpochSecond = Long.parseLong(payloadParts[1]);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (Instant.now().isAfter(Instant.ofEpochSecond(expiresAtEpochSecond))) {
            return null;
        }

        return payloadParts[0];
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
