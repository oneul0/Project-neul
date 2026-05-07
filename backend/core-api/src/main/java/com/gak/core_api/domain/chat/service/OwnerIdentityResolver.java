package com.gak.core_api.domain.chat.service;

import com.gak.common.auth.OwnerTokenCodec;
import com.gak.core_api.config.OwnerAccessFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
public class OwnerIdentityResolver {

    @Value("${gak.owner-token-secret:dev-gak-token-secret}")
    private String ownerTokenSecret;

    /**
     * OwnerAccessFilter가 이미 검증하고 주입한 ownerId를 우선 사용합니다.
     * 필터 범위 밖 경로에서 호출될 경우 쿠키를 직접 검증합니다 (sessionId 바인딩 없음).
     */
    public String resolveOwnerId(ServerWebExchange exchange) {
        Object cached = exchange.getAttribute(OwnerAccessFilter.ATTR_OWNER_ID);
        if (cached instanceof String s && !s.isBlank()) {
            return s;
        }
        if (exchange.getRequest().getCookies().containsKey("GAK_OWNER_ASSERTION")) {
            return OwnerTokenCodec.verifyAndExtractOwner(
                    exchange.getRequest().getCookies().getFirst("GAK_OWNER_ASSERTION").getValue(),
                    ownerTokenSecret);
        }
        return null;
    }
}
