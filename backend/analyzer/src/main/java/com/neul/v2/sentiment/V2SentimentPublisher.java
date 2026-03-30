package com.neul.v2.sentiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.v2.common.dto.V2SentimentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2SentimentPublisher {

    private static final String V2_SENTIMENT_TOPIC = "v2-sentiment";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(V2SentimentResult result) {
        try {
            kafkaTemplate.send(V2_SENTIMENT_TOPIC, result.getRoomId(), objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("[V2SentimentPublisher] Failed to serialize sentiment result. roomId={}", result.getRoomId(), e);
        }
    }
}
