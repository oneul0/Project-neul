package com.neul.collector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import com.neul.collector.jni.NativeBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
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
    private final NativeBridge nativeBridge;
    private final ObjectMapper objectMapper;

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
                .bodyToMono(JsonNode.class)
                .map(node -> node.path("content").path("chatChannelId").asText())
                .filter(id -> !id.isEmpty())
                .switchIfEmpty(Mono.error(new RuntimeException("Could not find chatChannelId for " + channelId)));
    }

    private Mono<String> getAccessToken(String chatChannelId) {
        return chzzkWebClient.get()
                .uri("https://comm-api.game.naver.com/web/v1/chat/access-token?channelId=" + chatChannelId + "&chatType=STREAMING")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(node -> node.path("content").path("accessToken").asText())
                .filter(token -> !token.isEmpty())
                .switchIfEmpty(Mono.error(new RuntimeException("Could not get access token for " + chatChannelId)));
    }

    // ─── 웹소켓 연결 및 수집 ───────────────────────────────────────────────────

    private Disposable connectAndCollect(String channelId, String chatChannelId, String accessToken) {
        // 메시지 수집을 위한 Sink (1분 배칭용)
        Sinks.Many<RawChatMessage> chatSink = Sinks.many().multicast().onBackpressureBuffer();

        // 1분 단위 배칭 파이프라인 (유저 요청 반영: "1분 단위로 묶어서 제공")
        Disposable batchJob = chatSink.asFlux()
                .window(Duration.ofMinutes(1))
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

        // 웹소켓 연결
        URI uri = URI.create("wss://kr-ss1.chat.naver.com/chat");
        
        Disposable wsJob = wsClient.execute(uri, session -> 
            session.send(Mono.just(session.textMessage(buildHandshake(chatChannelId, accessToken))))
                .thenMany(session.receive()
                        .map(msg -> nativeBridge.preprocessChat(msg.getPayloadAsText())) // Native Bridge 연동
                        .flatMap(this::parseAndEmit)
                        .doOnNext(chatSink::tryEmitNext)
                ).then()
        ).subscribe(
            null,
            err -> {
                log.error("[NidChat] Error in websocket for channel {}: {}", channelId, err.getMessage());
                unsubscribe(channelId);
            }
        );

        return () -> {
            wsJob.dispose();
            batchJob.dispose();
        };
    }

    private String buildHandshake(String chatChannelId, String accessToken) {
        Map<String, Object> handshake = Map.of(
            "ver", "3",
            "cmd", 100,
            "svcid", "game",
            "cid", chatChannelId,
            "bdy", Map.of(
                "uid", null,
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

    private Flux<RawChatMessage> parseAndEmit(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            int cmd = root.path("cmd").asInt();

            // 93101: Chat Message
            if (cmd == 93101) {
                JsonNode body = root.path("bdy");
                if (body.isArray()) {
                    return Flux.fromIterable(body)
                            .map(msgNode -> buildRawMessage(msgNode));
                }
            }
        } catch (Exception e) {
            log.trace("[NidChat] Non-chat or unparseable message received");
        }
        return Flux.empty();
    }

    private RawChatMessage buildRawMessage(JsonNode msgNode) {
        // 프로토콜 분석 결과에 기초한 매핑
        String extra = msgNode.path("extras").asText();
        String senderNickname = "Anonymous";
        try {
            JsonNode extraNode = objectMapper.readTree(extra);
            senderNickname = extraNode.path("extra").path("userName").asText("Anonymous");
        } catch (Exception e) {}

        long timeMs = msgNode.path("msgTime").asLong(System.currentTimeMillis());

        return RawChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .roomId(msgNode.path("cid").asText())
                .messageType("CHAT")
                .sender(senderNickname)
                .content(msgNode.path("msg").asText())
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()))
                .build();
    }
}
