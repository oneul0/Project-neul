package com.neul.core_api.domain.chat.service;

import com.neul.common.dto.VoteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 투표 세션 및 키워드 데이터 관리 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String VOTE_STATE_KEY = "vote:state:";
    private static final String VOTE_KEYWORDS_KEY = "vote:keywords:";

    public Mono<Void> startVote(VoteRequest request) {
        String key = VOTE_STATE_KEY + request.getRoomId();
        String keywordKey = VOTE_KEYWORDS_KEY + request.getRoomId();

        log.info("[Vote] Starting vote for room {}: {}", request.getRoomId(), request.getTitle());

        // 1. 기존 키워드 데이터 초기화
        return redisTemplate.delete(keywordKey)
                .then(redisTemplate.opsForValue().set(key, "ACTIVE"))
                .then(Mono.fromRunnable(() -> {
                    // 2. Kafka를 통해 Collector/Analyzer에 투표 시작 알림
                    kafkaTemplate.send("vote-events-topic", request.getRoomId(), request);
                }))
                .then();
    }

    public Mono<Void> stopVote(String roomId) {
        String key = VOTE_STATE_KEY + roomId;
        log.info("[Vote] Stopping vote for room {}", roomId);

        return redisTemplate.opsForValue().set(key, "INACTIVE")
                .then(Mono.fromRunnable(() -> {
                    kafkaTemplate.send("vote-events-topic", roomId, VoteRequest.builder()
                            .roomId(roomId)
                            .active(false)
                            .build());
                }))
                .then();
    }

    public Mono<Map<String, String>> getKeywords(String roomId) {
        String keywordKey = VOTE_KEYWORDS_KEY + roomId;
        return redisTemplate.opsForHash().entries(keywordKey)
                .collectMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString()
                );
    }
}
