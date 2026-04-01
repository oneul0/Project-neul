package com.neul.core_api.domain.chat.service;

import com.neul.common.auth.OwnerTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class OwnerIdentityResolver {

    @Value("${neul.owner-token-secret:dev-owner-token-secret}")
    private String ownerTokenSecret;

    public String resolveOwnerId(ServerHttpRequest request) {
        if (request.getCookies().containsKey("NEUL_OWNER_ASSERTION")) {
            String resolved = OwnerTokenCodec.verifyAndExtractOwner(
                    request.getCookies().getFirst("NEUL_OWNER_ASSERTION").getValue(),
                    ownerTokenSecret
            );
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }

        String headerOwnerId = request.getHeaders().getFirst("X-Chzzk-Owner-Id");
        if (headerOwnerId != null && !headerOwnerId.isBlank()) {
            return headerOwnerId;
        }

        String queryOwnerId = request.getQueryParams().getFirst("ownerId");
        if (queryOwnerId != null && !queryOwnerId.isBlank()) {
            return queryOwnerId;
        }

        return null;
    }
}
