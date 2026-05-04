package com.neul.collector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private static final String DONATION_TOPIC = "donation-events";

    public void sendDonation(RawChatMessage donation) {
        try {
            String json = objectMapper.writeValueAsString(donation);
            kafkaTemplate.send(DONATION_TOPIC, donation.getRoomId(), json);
            log.info("[DonationProducer] Sent: roomId={}, sender={}, amount={}",
                    donation.getRoomId(), donation.getSender(), donation.getPayAmount());
        } catch (JsonProcessingException e) {
            log.error("[DonationProducer] Serialize failed", e);
        }
    }
}
