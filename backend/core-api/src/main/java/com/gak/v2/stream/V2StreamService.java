package com.gak.v2.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.V2AggregateFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2StreamService {

    private final ObjectMapper objectMapper;
    private final V2RedisStateService v2RedisStateService;
    private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();
    private final Map<String, V2AggregateFrame> latestFrames = new ConcurrentHashMap<>();

    @KafkaListener(topics = "v2-aggregate", groupId = "gak-v2-core-api-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeAggregate(String json) {
        try {
            V2AggregateFrame frame = objectMapper.readValue(json, V2AggregateFrame.class);
            latestFrames.put(frame.getRoomId(), frame);
            v2RedisStateService.saveLatestFrame(frame).subscribe();

            Sinks.Many<Object> sink = roomSinks.get(frame.getRoomId());
            if (sink != null) {
                sink.tryEmitNext(Map.of("event", "v2_frame", "data", frame));
            }
        } catch (JsonProcessingException e) {
            log.error("[V2StreamService] Failed to parse v2 aggregate payload", e);
        }
    }

    public Flux<Object> subscribeRoom(String roomId) {
        Sinks.Many<Object> sink = roomSinks.computeIfAbsent(roomId, key -> Sinks.many().replay().limit(20));
        V2AggregateFrame latest = latestFrames.get(roomId);
        Mono<Object> initialFrame = latest != null
                ? Mono.just(Map.of("event", "v2_frame", "data", latest))
                : v2RedisStateService.getLatestFrame(roomId)
                        .map(frame -> {
                            latestFrames.put(roomId, frame);
                            return Map.of("event", "v2_frame", "data", frame);
                        });

        return initialFrame
                .flux()
                .concatWith(sink.asFlux())
                .doFinally(signalType -> {
                    if (sink.currentSubscriberCount() == 0) {
                        roomSinks.remove(roomId);
                    }
                });
    }
}
