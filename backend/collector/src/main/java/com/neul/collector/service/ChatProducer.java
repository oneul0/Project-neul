package com.neul.collector.service;

import com.neul.common.dto.RawChatBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProducer {

    private final KafkaTemplate<String, RawChatBatch> kafkaTemplate;
    private static final String TOPIC = "raw-chat-batch-topic";

    public void sendBatch(RawChatBatch batch) {
        log.debug("Sending chat batch to Kafka. RoomId: {}, count={}", batch.getRoomId(), batch.getMessages().size());
        kafkaTemplate.send(TOPIC, batch.getRoomId(), batch);
    }
}
