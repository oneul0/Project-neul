package com.neul.collector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import com.neul.collector.jni.NativeBridge;
import com.neul.collector.v2.producer.V2ChatProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
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
    private final V2ChatProducer v2ChatProducer;
    private final NativeBridge nativeBridge;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

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

    private Mono<String> getChatChannelId(String channelId) {
        return chzzkWebClient.get()
                .uri("https://api.chzzk.naver.com/polling/v2/channels/" + channelId + "/live-status")
                .retrieve()
                .bodyToMono(ObjectNode.class)
                .map(node -> {
                    JsonNode content = node.path("content");
                    if (content.isMissingNode() || content.isNull()) {
                        log.error("[NidChat] Chzzk API returned empty content for channel {}. Maybe adult-only or restricted? Response: {}", channelId, node);
                        return "";
                    }
                    String id = content.path("chatChannelId").asText();
                    if (!id.isEmpty()) {
                        log.info("[NidChat] Fetched chatChannelId: {} for channel: {}", id, channelId);
                    }
                    return id;
                })
                .filter(id -> !id.isEmpty())
                .switchIfEmpty(Mono.error(new RuntimeException("Could not find chatChannelId for " + channelId + ". Check if the channel is adult-only or restricted.")));
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
        // 프로토콜 분석 결과에 기초한 매핑
        String extra = msgNode.path("extras").asText();
        String senderNickname = "Anonymous";
        String messageType = "CHAT";
        
        if (cmd == 93102) messageType = "DONATION";
        else if (cmd == 93103) messageType = "SUBSCRIPTION";

        String senderId = "Anonymous";
        try {
            JsonNode extraNode = objectMapper.readTree(extra);
            senderId = extraNode.path("uid").asText("Anonymous");
            senderNickname = extraNode.path("extra").path("userName").asText(
                extraNode.path("nickname").asText("Anonymous")
            );
        } catch (Exception e) {}

        long timeMs = msgNode.path("msgTime").asLong(System.currentTimeMillis());

        String content = msgNode.path("msg").asText();
        if (content != null) {
            content = content.trim().replaceAll(":[\\w_.]+:", "[이모티콘]");
        }

        return RawChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .roomId(roomId) // Use the original long channelId
                .messageType(messageType)
                .sender(senderNickname)
                .senderId(senderId)
                .content(content)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()))
                .build();
    }
}
