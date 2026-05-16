package com.gak.collector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.RawChatBatch;
import com.gak.common.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private static final String BATCH_TOPIC = "raw-chat-batch-topic";
    private static final String CHAT_TOPIC = "raw-chat-topic";

    public void sendBatch(RawChatBatch batch) {
        try {
            log.debug("Sending chat batch to Kafka. RoomId: {}, count={}", batch.getRoomId(), batch.getMessages().size());
            String json = objectMapper.writeValueAsString(batch);
            kafkaTemplate.send(BATCH_TOPIC, batch.getRoomId(), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RawChatBatch", e);
        }
    }

    public void sendChat(RawChatMessage chat) {
        try {
            log.debug("Sending individual chat to Kafka. RoomId: {}, content={}", chat.getRoomId(), chat.getContent());
            String json = objectMapper.writeValueAsString(chat);
            kafkaTemplate.send(CHAT_TOPIC, chat.getRoomId(), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize RawChatMessage", e);
        }
    }
}
