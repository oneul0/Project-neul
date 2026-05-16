package com.gak.v2.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.v2.common.dto.V2RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2RawChatBootstrapConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "v2-raw-chat",
            groupId = "gak-v2-bootstrap-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(String json) {
        try {
            V2RawChatMessage message = objectMapper.readValue(json, V2RawChatMessage.class);
            log.debug("[V2Bootstrap] Received v2 raw chat. roomId={}, senderId={}, messageId={}",
                    message.getRoomId(), message.getSenderId(), message.getMessageId());
        } catch (JsonProcessingException e) {
            log.error("[V2Bootstrap] Failed to parse v2 raw chat payload", e);
        }
    }
}
