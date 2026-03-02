package com.neul.collector.service;

import com.neul.collector.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProducer {

    private final KafkaTemplate<String, RawChatMessage> kafkaTemplate;
    private static final String TOPIC = "raw-chat-topic";

    public void sendChat(RawChatMessage chatMessage) {
        log.debug("Sending chat message to Kafka. RoomId: {}, MessageId: {}", chatMessage.getRoomId(), chatMessage.getMessageId());
        // Use roomId as the partition key to ensure order of messages per room
        kafkaTemplate.send(TOPIC, chatMessage.getRoomId(), chatMessage);
    }
}
