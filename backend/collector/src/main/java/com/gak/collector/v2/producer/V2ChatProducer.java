package com.gak.collector.v2.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.common.dto.RawChatMessage;
import com.gak.v2.common.dto.V2RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2ChatProducer {

    private static final String V2_RAW_CHAT_TOPIC = "v2-raw-chat";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void sendRawChat(RawChatMessage rawChatMessage) {
        if (rawChatMessage == null || !"CHAT".equals(rawChatMessage.getMessageType())) {
            return;
        }

        V2RawChatMessage message = V2RawChatMessage.builder()
                .messageId(rawChatMessage.getMessageId())
                .roomId(rawChatMessage.getRoomId())
                .senderId(rawChatMessage.getSenderId())
                .sender(rawChatMessage.getSender())
                .messageType(rawChatMessage.getMessageType())
                .content(rawChatMessage.getContent())
                .timestamp(rawChatMessage.getTimestamp())
                .userRoleCode(rawChatMessage.getUserRoleCode())
                .build();

        try {
            kafkaTemplate.send(V2_RAW_CHAT_TOPIC, message.getRoomId(), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.error("[V2ChatProducer] Failed to serialize v2 raw chat. roomId={}", message.getRoomId(), e);
        }
    }
}
