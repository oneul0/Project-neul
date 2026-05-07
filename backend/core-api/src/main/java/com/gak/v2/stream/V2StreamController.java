package com.gak.v2.stream;

import com.gak.v2.common.dto.V2AggregateFrame;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class V2StreamController {

    private final V2StreamService v2StreamService;
    private final V2RedisStateService v2RedisStateService;

    @GetMapping("/state/{roomId}")
    public Mono<V2AggregateFrame> state(@PathVariable String roomId) {
        return v2RedisStateService.getLatestFrame(roomId);
    }

    @GetMapping(value = "/stream/{roomId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@PathVariable String roomId) {
        Flux<ServerSentEvent<Object>> eventStream = v2StreamService.subscribeRoom(roomId)
                .flatMap(payload -> {
                    if (payload instanceof Map<?, ?> dataMap) {
                        String eventName = (String) dataMap.get("event");
                        Object data = dataMap.get("data");

                        return Mono.just(ServerSentEvent.builder()
                                .event(eventName)
                                .data(data)
                                .build());
                    }
                    return Mono.empty();
                });

        Flux<ServerSentEvent<Object>> keepAliveStream = Flux.interval(Duration.ofSeconds(15))
                .map(sequence -> ServerSentEvent.builder()
                        .event("ping")
                        .data("keep-alive")
                        .build());

        return Flux.merge(eventStream, keepAliveStream);
    }
}
