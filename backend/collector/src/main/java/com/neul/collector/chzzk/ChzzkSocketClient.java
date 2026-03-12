package com.neul.collector.chzzk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.collector.dto.RawChatMessage;
import com.neul.collector.service.ChatProducer;
import io.socket.client.IO;
import io.socket.client.Socket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chzzk Open API Socket.IO 클라이언트.
 * <p>
 * 동작 흐름:
 * 1. ChzzkApiClient로 Socket.IO 세션 URL 발급
 * 2. Socket.IO 연결 → "connected" 시스템 메시지 수신 → sessionKey 저장
 * 3. 채팅/후원/구독 이벤트 구독 API 호출
 * 4. 이벤트 수신 → Kafka raw-chat-topic publish
 * <p>
 * 지원 Chzzk Socket.IO 버전: 1.0.0 ~ 2.0.3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChzzkSocketClient {

    private final ChzzkApiClient apiClient;
    private final ChatProducer chatProducer;
    private final ObjectMapper objectMapper;

    /** channelId → Socket 연결 관리 */
    private final Map<String, Socket> activeSockets = new ConcurrentHashMap<>();

    /** channelId → sessionKey */
    private final Map<String, String> sessionKeys = new ConcurrentHashMap<>();

    // ─── 채널 구독 ────────────────────────────────────────────────────────────

    /**
     * 특정 채널의 채팅/후원/구독 이벤트 구독을 시작.
     *
     * @param channelId Chzzk 채널 ID
     */
    public Mono<Void> subscribe(String channelId) {
        if (activeSockets.containsKey(channelId)) {
            log.warn("[Chzzk] Channel {} is already subscribed.", channelId);
            return Mono.empty();
        }

        return apiClient.createUserSession()
                .flatMap(socketUrl -> connectSocket(channelId, socketUrl))
                .onErrorMap(e -> {
                    log.error("[Chzzk] Failed to subscribe channel {}: {}", channelId, e.getMessage());
                    return new ChzzkApiException("Subscription failed for channel " + channelId, e);
                });
    }

    /**
     * 특정 채널 구독 취소 및 연결 해제.
     */
    public Mono<Void> unsubscribe(String channelId) {
        String sessionKey = sessionKeys.remove(channelId);
        Socket socket = activeSockets.remove(channelId);

        if (socket == null) {
            log.warn("[Chzzk] No active socket found for channel: {}", channelId);
            return Mono.empty();
        }

        Mono<Void> unsubscribeMono = sessionKey != null
                ? Mono.when(
                        apiClient.unsubscribeEvent("chat", sessionKey),
                        apiClient.unsubscribeEvent("donation", sessionKey),
                        apiClient.unsubscribeEvent("subscription", sessionKey)).onErrorResume(e -> {
                            log.warn("[Chzzk] Unsubscribe API call failed (will still disconnect): {}", e.getMessage());
                            return Mono.empty();
                        })
                : Mono.empty();

        return unsubscribeMono.doFinally(signal -> {
            socket.disconnect();
            log.info("[Chzzk] Disconnected from channel: {}", channelId);
        });
    }

    public boolean isSubscribed(String channelId) {
        return activeSockets.containsKey(channelId);
    }

    // ─── Socket.IO 연결 ───────────────────────────────────────────────────────

    private Mono<Void> connectSocket(String channelId, String socketUrl) {
        return Mono.create(sink -> {
            try {
                IO.Options opts = new IO.Options();
                opts.transports = new String[] { "websocket" };
                opts.reconnection = true;
                opts.reconnectionAttempts = 5;
                opts.reconnectionDelay = 5000;

                Socket socket = IO.socket(URI.create(socketUrl), opts);
                activeSockets.put(channelId, socket);

                // ── 시스템 및 채팅 이벤트 수신 ──
                socket.on("SYSTEM", args -> {
                    try {
                        JSONObject msg = parseArgs(args);
                        handleSystemMessage(channelId, msg, sink);
                    } catch (Exception e) {
                        log.error("[Chzzk][{}] Error parsing SYSTEM message: {}", channelId, e.getMessage());
                    }
                });

                socket.on("CHAT", args -> {
                    try {
                        JSONObject msg = parseArgs(args);
                        handleChatMessage(channelId, msg);
                    } catch (Exception e) {
                        log.error("[Chzzk][{}] Error parsing CHAT message: {}", channelId, e.getMessage());
                    }
                });

                socket.on("DONATION", args -> {
                    try {
                        JSONObject msg = parseArgs(args);
                        handleDonationMessage(channelId, msg);
                    } catch (Exception e) {
                        log.error("[Chzzk][{}] Error parsing DONATION message: {}", channelId, e.getMessage());
                    }
                });

                socket.on("SUBSCRIPTION", args -> {
                    try {
                        JSONObject msg = parseArgs(args);
                        handleSubscriptionMessage(channelId, msg);
                    } catch (Exception e) {
                        log.error("[Chzzk][{}] Error parsing SUBSCRIPTION message: {}", channelId, e.getMessage());
                    }
                });

                socket.on(Socket.EVENT_CONNECT, a -> log
                        .info("[Chzzk][{}] Socket.IO transport connected. Waiting for SYSTEM/connected...", channelId));

                socket.on(Socket.EVENT_CONNECT_ERROR, a -> {
                    log.error("[Chzzk][{}] Socket.IO connect error: {}", channelId, a[0]);
                    activeSockets.remove(channelId);
                    sink.error(new ChzzkApiException("Socket.IO connect error for channel " + channelId));
                });

                socket.on(Socket.EVENT_DISCONNECT,
                        a -> log.warn("[Chzzk][{}] Socket.IO disconnected: {}", channelId, a[0]));

                socket.connect();

            } catch (Exception e) {
                activeSockets.remove(channelId);
                sink.error(new ChzzkApiException("Failed to create Socket.IO connection", e));
            }
        });
    }

    private JSONObject parseArgs(Object[] args) {
        if (args == null || args.length == 0) return new JSONObject();
        Object arg = args[0];
        try {
            if (arg instanceof String raw) {
                return new JSONObject(raw);
            }
            return (JSONObject) arg;
        } catch (org.json.JSONException e) {
            log.error("Failed to parse args into JSONObject", e);
            return new JSONObject();
        }
    }

    // ─── 시스템 메시지 처리 ────────────────────────────────────────────────────

    private void handleSystemMessage(String channelId, JSONObject msg, reactor.core.publisher.MonoSink<Void> sink) {
        try {
            JSONObject data = msg.getJSONObject("data");
            String type = msg.getString("type");

            if ("connected".equals(type)) {
                String sessionKey = data.getString("sessionKey");
                sessionKeys.put(channelId, sessionKey);
                log.info("[Chzzk][{}] Session connected. sessionKey: {}", channelId, sessionKey);

                // 이벤트 구독 API 호출 (각각 독립적으로 처리, 일부 실패해도 계속)
                Mono<Void> chatSub = apiClient.subscribeChatEvent(sessionKey)
                        .doOnSuccess(v -> log.info("[Chzzk][{}] Chat event subscribed.", channelId))
                        .onErrorResume(e -> { log.warn("[Chzzk][{}] Chat subscribe failed (권한 부족?): {}", channelId, e.getMessage()); return Mono.empty(); });

                Mono<Void> donationSub = apiClient.subscribeDonationEvent(sessionKey)
                        .doOnSuccess(v -> log.info("[Chzzk][{}] Donation event subscribed.", channelId))
                        .onErrorResume(e -> { log.warn("[Chzzk][{}] Donation subscribe failed (권한 부족?): {}", channelId, e.getMessage()); return Mono.empty(); });

                Mono<Void> subscriptionSub = apiClient.subscribeSubscriptionEvent(sessionKey)
                        .doOnSuccess(v -> log.info("[Chzzk][{}] Subscription event subscribed.", channelId))
                        .onErrorResume(e -> { log.warn("[Chzzk][{}] Subscription subscribe failed (권한 부족?): {}", channelId, e.getMessage()); return Mono.empty(); });

                Mono.when(chatSub, donationSub, subscriptionSub).subscribe(
                                v -> {
                                    log.info("[Chzzk][{}] Event subscription process completed.", channelId);
                                    sink.success();
                                },
                                e -> {
                                    log.error("[Chzzk][{}] Event subscription failed: {}", channelId, e.getMessage());
                                    sink.error(e);
                                });
            } else if ("revoked".equals(type)) {
                log.warn("[Chzzk][{}] Event permission revoked: {}", channelId, data);
            }
        } catch (Exception e) {
            log.error("[Chzzk][{}] Error handling system message: {}", channelId, e.getMessage());
        }
    }

    // ─── 채팅 이벤트 메시지 변환 ────────────────────────────────────────────

    private void handleChatMessage(String channelId, JSONObject msg) {
        try {
            JSONObject data = msg.getJSONObject("data");
            JSONObject profile = data.optJSONObject("profile");

            String sender = profile != null ? profile.optString("nickname", "unknown") : "unknown";
            String userRoleCode = profile != null ? profile.optString("userRoleCode", "common_user") : "common_user";
            long messageTimeMs = data.optLong("messageTime", System.currentTimeMillis());

            RawChatMessage raw = RawChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .roomId(channelId)
                    .messageType("CHAT")
                    .sender(sender)
                    .content(data.optString("content", ""))
                    .userRoleCode(userRoleCode)
                    .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(messageTimeMs), ZoneId.systemDefault()))
                    .build();

            chatProducer.sendChat(raw);

        } catch (Exception e) {
            log.error("[Chzzk][{}] Error parsing CHAT message: {}", channelId, e.getMessage());
        }
    }

    // ─── 후원 이벤트 메시지 변환 ────────────────────────────────────────────

    private void handleDonationMessage(String channelId, JSONObject msg) {
        try {
            JSONObject data = msg.getJSONObject("data");

            RawChatMessage raw = RawChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .roomId(channelId)
                    .messageType("DONATION")
                    .donationType(data.optString("donationType", "CHAT"))
                    .donatorNickname(data.optString("donatorNickname", "anonymous"))
                    .payAmount(data.optString("payAmount", "0"))
                    .donationText(data.optString("donationText", ""))
                    .timestamp(LocalDateTime.now())
                    .build();

            chatProducer.sendChat(raw);
            log.debug("[Chzzk][{}] Donation received: {}원 from {}", channelId, raw.getPayAmount(),
                    raw.getDonatorNickname());

        } catch (Exception e) {
            log.error("[Chzzk][{}] Error parsing DONATION message: {}", channelId, e.getMessage());
        }
    }

    // ─── 구독 이벤트 메시지 변환 ────────────────────────────────────────────

    private void handleSubscriptionMessage(String channelId, JSONObject msg) {
        try {
            JSONObject data = msg.getJSONObject("data");

            RawChatMessage raw = RawChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .roomId(channelId)
                    .messageType("SUBSCRIPTION")
                    .subscriberNickname(data.optString("subscriberNickname", "anonymous"))
                    .tierNo(data.optInt("tierNo", 1))
                    .tierName(data.optString("tierName", ""))
                    .month(data.optInt("month", 1))
                    .timestamp(LocalDateTime.now())
                    .build();

            chatProducer.sendChat(raw);
            log.debug("[Chzzk][{}] Subscription: tier{} {}개월 from {}", channelId,
                    raw.getTierNo(), raw.getMonth(), raw.getSubscriberNickname());

        } catch (Exception e) {
            log.error("[Chzzk][{}] Error parsing SUBSCRIPTION message: {}", channelId, e.getMessage());
        }
    }
}
