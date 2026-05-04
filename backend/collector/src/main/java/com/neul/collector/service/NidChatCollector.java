package com.neul.collector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import com.neul.collector.auth.ChzzkAuthStore;
import com.neul.collector.auth.ChzzkSessionRegistry;
import com.neul.collector.config.ChzzkProperties;
import com.neul.collector.jni.NativeBridge;
import com.neul.collector.v2.producer.V2ChatProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 치지직 내부 웹소켓 프로토콜(NID Chat)을 이용한 고성능 수집기.
 * - 1분 단위 배치(Batching) 처리 및 JNI 연동을 고려한 구조.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class NidChatCollector implements ChatCollector {

    private final WebClient chzzkWebClient;
    private final ChatProducer chatProducer;
    private final DonationProducer donationProducer;
    private final V2ChatProducer v2ChatProducer;
    private final NativeBridge nativeBridge;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ChzzkSessionRegistry sessionRegistry;
    private final ChzzkAuthStore authStore;
    private final ChzzkProperties chzzkProperties;

    private final Map<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
    private final WebSocketClient wsClient = new ReactorNettyWebSocketClient();

    @Override
    public Mono<Void> subscribe(String channelId) {
        if (activeSubscriptions.containsKey(channelId)) {
            log.warn("[NidChat] Channel {} is already being collected.", channelId);
            return Mono.empty();
        }

        log.info("[NidChat] Starting collection for channel: {}", channelId);

        // 1. 내부 API를 통해 chatChannelId(네이버 채팅 고유 ID) 조회
        return getChatChannelId(channelId)
                .flatMap(chatChannelId -> getAccessToken(chatChannelId)
                        .flatMap(accessToken -> {
                            // 2. 웹소켓 연결 및 데이터 처리 파이프라인 시작
                            Disposable disposable = connectAndCollect(channelId, chatChannelId, accessToken);
                            activeSubscriptions.put(channelId, disposable);
                            return Mono.empty();
                        }))
                .then();
    }

    @Override
    public Mono<Void> unsubscribe(String channelId) {
        Disposable disposable = activeSubscriptions.remove(channelId);
        if (disposable != null) {
            disposable.dispose();
            log.info("[NidChat] Stopped collection for channel: {}", channelId);
        }
        return Mono.empty();
    }

    @Override
    public boolean isSubscribed(String channelId) {
        return activeSubscriptions.containsKey(channelId);
    }

    // ─── 내부 API 호출 ────────────────────────────────────────────────────────

    /**
     * chatChannelId 조회.
     *  1단계: NID 비공식 API (쿼터 없음, 성인 방송 불가)
     *  2단계: NID 실패 시 → 공식 Open API (OAuth access_token 필요)
     *  공식 API에도 토큰이 없으면 AdultStreamException 발생
     */
    private Mono<String> getChatChannelId(String channelId) {
        return getChatChannelIdViaNid(channelId)
                .switchIfEmpty(
                    tryOAuthFallback(channelId)
                );
    }

    private Mono<String> getChatChannelIdViaNid(String channelId) {
        return chzzkWebClient.get()
                .uri("https://api.chzzk.naver.com/polling/v2/channels/" + channelId + "/live-status")
                .retrieve()
                .bodyToMono(ObjectNode.class)
                .flatMap(node -> {
                    JsonNode content = node.path("content");
                    if (content.isMissingNode() || content.isNull()) {
                        log.warn("[NidChat] NID API returned empty content for channel={}. Possibly adult-only. Will try OAuth fallback.", channelId);
                        return Mono.empty(); // switchIfEmpty 트리거
                    }
                    String id = content.path("chatChannelId").asText("");
                    if (id.isBlank()) {
                        log.warn("[NidChat] chatChannelId empty for channel={}. Possibly offline.", channelId);
                        return Mono.empty();
                    }
                    log.info("[NidChat] chatChannelId via NID: {} for channel={}", id, channelId);
                    return Mono.just(id);
                });
    }

    /**
     * 공식 Open API (Client-Id/Secret)로 chatChannelId를 조회한다.
     * GET /open/v1/lives/{channelId} — adult 방송도 응답한다.
     * OAuth access_token이 없으면 AdultStreamException을 던진다.
     */
    private Mono<String> tryOAuthFallback(String channelId) {
        String clientId = chzzkProperties.getClientId();
        String clientSecret = chzzkProperties.getClientSecret();
        String baseUrl = chzzkProperties.getBaseUrl();

        boolean hasClientCredentials = clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && !clientId.contains("CHZZK_CLIENT_ID");

        if (!hasClientCredentials) {
            log.warn("[NidChat] No Chzzk API credentials. Cannot collect adult stream channel={}.", channelId);
            return Mono.error(new AdultStreamException(channelId, false));
        }

        log.info("[NidChat] Attempting Open API fallback for adult stream channel={}", channelId);

        return sessionRegistry.getSessionId(channelId)
                .flatMap(authStore::peekSession)
                .flatMap(session -> {
                    log.info("[NidChat] Found OAuth session for channel={}, trying official live API.", channelId);
                    return WebClient.builder().baseUrl(baseUrl).build()
                            .get()
                            .uri("/open/v1/lives/" + channelId)
                            .header("Client-Id", clientId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.getAccessToken())
                            .retrieve()
                            .bodyToMono(ObjectNode.class)
                            .flatMap(node -> {
                                JsonNode content = node.path("content");
                                if (content.isMissingNode() || content.isNull()) {
                                    log.warn("[NidChat] Official API also returned no content for channel={} (offline?).", channelId);
                                    return Mono.error(new RuntimeException("Channel " + channelId + " appears to be offline."));
                                }
                                String id = content.path("chatChannelId").asText("");
                                if (id.isBlank()) {
                                    return Mono.error(new RuntimeException("Official API returned no chatChannelId for " + channelId));
                                }
                                log.info("[NidChat] chatChannelId via Official API: {} (adult stream={})",
                                        id, content.path("adult").asBoolean(false));
                                return Mono.just(id);
                            });
                })
                .switchIfEmpty(
                    Mono.error(new AdultStreamException(channelId, true))
                );
    }

    // ─── 예외 타입 ────────────────────────────────────────────────────────────

    /**
     * 성인 방송 수집 불가 예외.
     * hasCredentials=true  → API credentials는 있지만 채널 소유자 OAuth 로그인 없음
     * hasCredentials=false → Chzzk API credentials 자체가 미설정
     */
    public static class AdultStreamException extends RuntimeException {
        private final String channelId;
        private final boolean hasCredentials;

        public AdultStreamException(String channelId, boolean hasCredentials) {
            super("Adult stream detected for channel=" + channelId
                    + (hasCredentials ? ": owner OAuth session not found." : ": API credentials not configured."));
            this.channelId = channelId;
            this.hasCredentials = hasCredentials;
        }

        public String getChannelId() { return channelId; }
        public boolean isHasCredentials() { return hasCredentials; }
    }

    private Mono<String> getAccessToken(String chatChannelId) {
        String url = "https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=" + chatChannelId + "&chatType=STREAMING";
        log.info("[NidChat] Requesting access token from: {}", url);
        return chzzkWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(ObjectNode.class)
                .map(node -> {
                    String token = node.path("content").path("accessToken").asText();
                    if (!token.isEmpty()) {
                        log.info("[NidChat] Successfully fetched access token (prefix: {})", 
                                token.substring(0, Math.min(5, token.length())));
                    } else {
                        log.error("[NidChat] Failed to get access token for {}. Response: {}", chatChannelId, node);
                    }
                    return token;
                })
                .filter(token -> !token.isEmpty())
                .switchIfEmpty(Mono.error(new RuntimeException("Could not get access token for " + chatChannelId)));
    }

    // ─── 웹소켓 연결 및 수집 ───────────────────────────────────────────────────

    private Disposable connectAndCollect(String channelId, String chatChannelId, String accessToken) {
        // 메시지 수집을 위한 Sink (1분 배칭용)
        Sinks.Many<RawChatMessage> chatSink = Sinks.many().multicast().onBackpressureBuffer();

        // 1. 2초 단위 배칭 파이프라인 (실시간성 강화)
        Disposable batchJob = chatSink.asFlux()
                .window(Duration.ofSeconds(2))
                .flatMap(window -> window.collectList())
                .filter(list -> !list.isEmpty())
                .subscribe(batchList -> {
                    log.info("[NidChat] Sending batch of {} messages for channel {}", batchList.size(), channelId);
                    chatProducer.sendBatch(RawChatBatch.builder()
                            .roomId(channelId)
                            .messages(batchList)
                            .batchTime(LocalDateTime.now())
                            .build());
                });

        // 2. 웹소켓 연결 및 자동 재시도 로직
        Disposable wsJob = connectWebsocket(channelId, chatChannelId, accessToken, chatSink)
            .subscribe(
                null,
                err -> {
                    log.error("[NidChat] Critical error in websocket for channel {}: {}", channelId, err.getMessage());
                    err.printStackTrace();
                    activeSubscriptions.remove(channelId);
                    batchJob.dispose();
                }
            );

        return () -> {
            wsJob.dispose();
            batchJob.dispose();
        };
    }

    private Mono<Void> connectWebsocket(String originalChannelId, String chatChannelId, String accessToken, Sinks.Many<RawChatMessage> chatSink) {
        URI uri = URI.create("wss://kr-ss1.chat.naver.com/chat");
        
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Origin", "https://chzzk.naver.com");
        headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        return wsClient.execute(uri, headers, session -> {
            log.info("[NidChat] WebSocket session established for channel: {}", originalChannelId);
            
            String handshakePayload = buildHandshake(chatChannelId, accessToken);
            log.info("[NidChat] Sending handshake: {}", handshakePayload);
            Mono<Void> handshake = session.send(Mono.just(session.textMessage(handshakePayload)));

            Flux<Void> pings = Flux.interval(Duration.ofSeconds(20))
                    .flatMap(i -> {
                        log.trace("[NidChat] Sending Ping for {}", originalChannelId);
                        return session.send(Mono.just(session.textMessage("{\"ver\":\"3\",\"cmd\":0}")));
                    })
                    .then().flux();

            Flux<Void> receive = session.receive()
                    .doOnNext(msg -> log.info("[NidChat] Received raw from {}: {}", originalChannelId, msg.getPayloadAsText()))
                    .flatMap(msg -> {
                        String text = nativeBridge.preprocessChat(msg.getPayloadAsText());
                        return parseAndEmit(originalChannelId, text).doOnNext(chatMsg -> {
                            if ("CHAT".equals(chatMsg.getMessageType())) {
                                chatSink.tryEmitNext(chatMsg);
                                v2ChatProducer.sendRawChat(chatMsg);
                            } else if ("DONATION".equals(chatMsg.getMessageType())) {
                                donationProducer.sendDonation(chatMsg);
                            } else {
                                chatProducer.sendChat(chatMsg);
                            }
                        }).then();
                    })
                    .doOnError(e -> log.error("[NidChat] Receive error for {}: {}", originalChannelId, e.getMessage()))
                    .then().flux();

            return Mono.when(handshake, pings, receive);
        })
        .retryWhen(reactor.util.retry.Retry.backoff(10, Duration.ofSeconds(2))
                .doBeforeRetry(retrySignal -> log.warn("[NidChat] Retrying connection for {}: attempt {}, error: {}", 
                        originalChannelId, retrySignal.totalRetries() + 1, retrySignal.failure().getMessage())));
    }


    private String buildHandshake(String chatChannelId, String accessToken) {
        Map<String, Object> handshake = Map.of(
            "ver", "3",
            "cmd", 100,
            "svcid", "game",
            "cid", chatChannelId,
            "bdy", Map.of(
                "uid", "",
                "devType", 2001,
                "accTkn", accessToken,
                "auth", "READ"
            ),
            "tid", 1
        );
        try {
            return objectMapper.writeValueAsString(handshake);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Flux<RawChatMessage> parseAndEmit(String roomId, String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            int cmd = root.path("cmd").asInt();

            // 93101: Chat Message, 93102: Donation, 93103: Subscription
            if (cmd == 93101 || cmd == 93102 || cmd == 93103) {
                JsonNode body = root.path("bdy");
                if (body.isArray()) {
                    return Flux.fromIterable(body)
                            .map(msgNode -> {
                                RawChatMessage msg = buildRawMessage(roomId, msgNode, cmd);
                                meterRegistry.counter("neul.chat.collected.total", "roomId", roomId).increment();
                                return msg;
                            });
                }
            }
        } catch (Exception e) {
            log.trace("[NidChat] Non-chat or unparseable message received");
        }
        return Flux.empty();
    }

    private RawChatMessage buildRawMessage(String roomId, JsonNode msgNode, int cmd) {
        String extra = msgNode.path("extras").asText();
        String senderNickname = "Anonymous";
        String messageType = "CHAT";

        if (cmd == 93102) messageType = "DONATION";
        else if (cmd == 93103) messageType = "SUBSCRIPTION";

        String senderId = "Anonymous";
        String payAmount = null;
        String donationType = null;
        try {
            JsonNode extraNode = objectMapper.readTree(extra);
            senderId = extraNode.path("uid").asText("Anonymous");
            senderNickname = extraNode.path("extra").path("userName").asText(
                extraNode.path("nickname").asText("Anonymous")
            );
            if (cmd == 93102) {
                payAmount = extraNode.path("payAmount").asText(null);
                donationType = extraNode.path("donationType").asText(null);
            }
        } catch (Exception e) {}

        long timeMs = msgNode.path("msgTime").asLong(System.currentTimeMillis());

        String content = msgNode.path("msg").asText();
        if (content != null) {
            content = content.trim().replaceAll(":[\\w_.]+:", "[이모티콘]");
        }

        RawChatMessage.RawChatMessageBuilder builder = RawChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .roomId(roomId)
                .messageType(messageType)
                .sender(senderNickname)
                .senderId(senderId)
                .content(content)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()));

        if (cmd == 93102) {
            builder.donatorNickname(senderNickname)
                   .donationText(content)
                   .payAmount(payAmount)
                   .donationType(donationType);
        }

        return builder.build();
    }
}
