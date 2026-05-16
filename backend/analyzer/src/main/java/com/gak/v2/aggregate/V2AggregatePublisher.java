package com.gak.v2.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.V2AggregateFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2AggregatePublisher {

    private static final String V2_AGGREGATE_TOPIC = "v2-aggregate";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(V2AggregateFrame frame) {
        try {
            kafkaTemplate.send(V2_AGGREGATE_TOPIC, frame.getRoomId(), objectMapper.writeValueAsString(frame));
        } catch (JsonProcessingException e) {
            log.error("[V2AggregatePublisher] Failed to serialize aggregate frame. roomId={}", frame.getRoomId(), e);
        }
    }
}
