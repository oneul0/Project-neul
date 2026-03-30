package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import com.neul.core_api.domain.chat.service.ChatStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    private final ChatStreamService chatStreamService;
    private final AnalyzedChatRepository analyzedChatRepository;

    @GetMapping(value = "/stream/{roomId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamChatAnalysis(@PathVariable String roomId) {
        
        Flux<ServerSentEvent<Object>> eventStream = chatStreamService.subscribeRoom(roomId)
                .flatMap(payload -> {
                    if (payload instanceof Map) {
                        Map<?, ?> dataMap = (Map<?, ?>) payload;
                        String eventName = (String) dataMap.get("event");
                        Object data = dataMap.get("data");

                        return Mono.just(ServerSentEvent.builder()
                                .event(eventName)
                                .data(data)
                                .build());
                    }
                    return Mono.empty();
                });

        // 클라이언트 연결 유지(Keep-Alive)를 위해 주기적으로 하트비트를 전송
        Flux<ServerSentEvent<Object>> keepAliveStream = Flux.interval(Duration.ofSeconds(15))
                .map(sequence -> ServerSentEvent.builder()
                        .event("ping")
                        .data("keep-alive")
                        .build());

        return Flux.merge(eventStream, keepAliveStream);
    }

    @GetMapping("/stream/{roomId}/history")
    public Flux<AnalyzedChat> getStreamHistory(@PathVariable String roomId) {
        return analyzedChatRepository.findRecentByRoomId(roomId)
                .onErrorResume(error -> {
                    log.error("[StreamHistory] Failed to fetch history for roomId={}: {}", roomId, error.getMessage(), error);
                    return Flux.empty();
                });
    }
}
