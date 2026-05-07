package com.gak.v2.troll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.V2TrollResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2TrollPublisher {

    private static final String V2_TROLL_TOPIC = "v2-troll";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(V2TrollResult result) {
        try {
            kafkaTemplate.send(V2_TROLL_TOPIC, result.getRoomId(), objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("[V2TrollPublisher] Failed to serialize troll result. roomId={}", result.getRoomId(), e);
        }
    }
}
