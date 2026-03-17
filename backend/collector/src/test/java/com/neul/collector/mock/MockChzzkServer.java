package com.neul.collector.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;

/**
 * 치지직(Chzzk) 웹소켓 프로토콜을 시뮬레이션하는 모크 서버 핸들러.
 */
@Slf4j
public class MockChzzkServer implements WebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Sinks.Many<String> messageSink = Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("[MockChzzk] New session established: {}", session.getId());

        // 1. 클라이언트로부터 수신된 핸드쉐이크 및 핑 처리
        Mono<Void> receive = session.receive()
                .doOnNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    log.info("[MockChzzk] Received: {}", payload);
                    handleClientMessage(session, payload);
                })
                .then();

        // 2. 서버에서 클라이언트로 메시지 푸시 (테스트용)
        Mono<Void> send = session.send(messageSink.asFlux()
                .map(session::textMessage));

        return Mono.zip(receive, send).then();
    }

    private void handleClientMessage(WebSocketSession session, String payload) {
        try {
            Map<String, Object> map = objectMapper.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            int cmd = (int) map.getOrDefault("cmd", -1);

            if (cmd == 100) { // Handshake Request
                log.info("[MockChzzk] Handshake received. Sending response...");
                String response = "{\"ver\":\"3\",\"cmd\":10100,\"bdy\":{\"sid\":\"mock-session-1\"}}";
                session.send(Mono.just(session.textMessage(response))).subscribe();
            } else if (cmd == 0) { // Ping
                log.info("[MockChzzk] Ping received. Sending Pong...");
                String response = "{\"ver\":\"3\",\"cmd\":10000}";
                session.send(Mono.just(session.textMessage(response))).subscribe();
            }
        } catch (Exception e) {
            log.error("[MockChzzk] Failed to parse message", e);
        }
    }

    /**
     * 외부(테스트 코드)에서 가상 채팅 메시지를 주입합니다.
     */
    public void pushChatMessage(String roomId, String sender, String content) {
        try {
            Map<String, Object> msg = Map.of(
                "cmd", 93101,
                "bdy", java.util.List.of(Map.of(
                    "msg", content,
                    "msgTime", System.currentTimeMillis(),
                    "extras", "{\"uid\":\"" + sender + "\",\"extra\":{\"userName\":\"" + sender + "\"}}"
                ))
            );
            messageSink.tryEmitNext(objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("[MockChzzk] Failed to push chat message", e);
        }
    }
}
