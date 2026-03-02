package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.service.ChatStreamService;
import com.neul.core_api.domain.chat.service.StreamRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StreamController {

    private final ChatStreamService chatStreamService;
    private final StreamRedisService streamRedisService;

    @GetMapping(value = "/stream/{roomId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamChatAnalysis(@PathVariable String roomId) {
        
        Flux<ServerSentEvent<Object>> eventStream = chatStreamService.subscribeRoom(roomId)
                .map(payload -> {
                    Map<String, Object> dataMap = (Map<String, Object>) payload;
                    String eventName = (String) dataMap.get("event");
                    Object data = dataMap.get("data");

                    return ServerSentEvent.builder()
                            .event(eventName)
                            .data(data)
                            .build();
                });

        // 클라이언트 연결 유지(Keep-Alive)를 위해 주기적으로 하트비트를 전송
        Flux<ServerSentEvent<Object>> keepAliveStream = Flux.interval(Duration.ofSeconds(15))
                .map(sequence -> ServerSentEvent.builder()
                        .event("ping")
                        .data("keep-alive")
                        .build());

        return Flux.merge(eventStream, keepAliveStream);
    }
}
