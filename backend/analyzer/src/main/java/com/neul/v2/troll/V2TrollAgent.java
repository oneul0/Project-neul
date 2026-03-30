package com.neul.v2.troll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.v2.common.dto.V2RawChatMessage;
import com.neul.v2.common.dto.V2TrollResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2TrollAgent {

    private final ObjectMapper objectMapper;
    private final V2TrustScoreService trustScoreService;
    private final V2TrollPublisher trollPublisher;

    @KafkaListener(
            topics = "v2-raw-chat",
            groupId = "neul-v2-troll-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(String json) {
        try {
            V2RawChatMessage message = objectMapper.readValue(json, V2RawChatMessage.class);
            if (!"CHAT".equals(message.getMessageType())) {
                return;
            }

            trustScoreService.evaluate(message)
                    .doOnNext(this::publishResult)
                    .subscribe();
        } catch (JsonProcessingException e) {
            log.error("[V2TrollAgent] Failed to parse v2 raw chat payload", e);
        }
    }

    private void publishResult(V2TrollResult result) {
        trollPublisher.publish(result);
        log.debug("[V2TrollAgent] Published v2 troll result. roomId={}, senderId={}, trustGrade={}",
                result.getRoomId(), result.getSenderId(), result.getTrustGrade());
    }
}
