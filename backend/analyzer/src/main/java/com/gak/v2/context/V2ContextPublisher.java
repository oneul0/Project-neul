package com.gak.v2.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.V2ContextResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2ContextPublisher {

    private static final String V2_CONTEXT_TOPIC = "v2-context";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(V2ContextResult result) {
        try {
            kafkaTemplate.send(V2_CONTEXT_TOPIC, result.getRoomId(), objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("[V2ContextPublisher] Failed to serialize context result. roomId={}", result.getRoomId(), e);
        }
    }
}
