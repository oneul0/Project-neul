package com.neul.core_api.domain.chat.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.RawChatMessage;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "neul:donations:";
    private static final int MAX_POOL_SIZE = 200;

    @KafkaListener(topics = "donation-events", groupId = "neul-core-api-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeDonation(String json) {
        try {
            RawChatMessage donation = objectMapper.readValue(json, RawChatMessage.class);
            saveDonation(donation)
                .subscribe(
                    v -> log.debug("[Donation] Saved: roomId={}, sender={}", donation.getRoomId(), donation.getSender()),
                    err -> log.error("[Donation] Save failed: {}", err.getMessage())
                );
        } catch (JsonProcessingException e) {
            log.error("[Donation] Parse failed: {}", json, e);
        }
    }

    private Mono<Void> saveDonation(RawChatMessage donation) {
        String key = KEY_PREFIX + donation.getRoomId();
        try {
            String json = objectMapper.writeValueAsString(DonationEntry.from(donation));
            return redisTemplate.opsForList().rightPush(key, json)
                .flatMap(size -> size > MAX_POOL_SIZE
                    ? redisTemplate.opsForList().trim(key, -MAX_POOL_SIZE, -1)
                    : Mono.empty())
                .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    public Flux<DonationEntry> getDonations(String channelId) {
        String key = KEY_PREFIX + channelId;
        return redisTemplate.opsForList().range(key, 0, -1)
            .map(obj -> parseDonationEntry(obj.toString()))
            .filter(Objects::nonNull);
    }

    public Mono<DonationEntry> spin(String channelId) {
        return getDonations(channelId)
            .collectList()
            .flatMap(list -> {
                if (list.isEmpty()) return Mono.empty();
                int idx = ThreadLocalRandom.current().nextInt(list.size());
                return Mono.just(list.get(idx));
            });
    }

    public Mono<Void> clearDonations(String channelId) {
        return redisTemplate.delete(KEY_PREFIX + channelId).then();
    }

    private DonationEntry parseDonationEntry(String json) {
        try {
            return objectMapper.readValue(json, DonationEntry.class);
        } catch (Exception e) {
            log.warn("[Donation] Failed to parse entry: {}", json);
            return null;
        }
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DonationEntry {
        private String messageId;
        private String donorNickname;
        private String message;
        private String amount;
        private LocalDateTime timestamp;

        public static DonationEntry from(RawChatMessage msg) {
            String nickname = msg.getDonatorNickname() != null && !msg.getDonatorNickname().isBlank()
                ? msg.getDonatorNickname()
                : msg.getSender();
            String text = msg.getDonationText() != null ? msg.getDonationText() : msg.getContent();
            return DonationEntry.builder()
                .messageId(msg.getMessageId())
                .donorNickname(nickname)
                .message(text)
                .amount(msg.getPayAmount())
                .timestamp(msg.getTimestamp())
                .build();
        }
    }
}
