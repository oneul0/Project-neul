package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VoteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실시간 투표 상태를 관리하는 서비스.
 * analyzer 모듈에서 어떤 채널이 투표 중인지 추적합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteStateService {

    private final ObjectMapper objectMapper;
    private final Map<String, VoteRequest> activeVotes = new ConcurrentHashMap<>();

    @KafkaListener(topics = "vote-events-topic", groupId = "neul-analyzer-vote-group")
    public void handleVoteEvent(String eventJson) {
        try {
            VoteRequest request = objectMapper.readValue(eventJson, VoteRequest.class);
            if (request.isActive()) {
                log.info("[VoteState] Starting vote tracking for room: {}", request.getRoomId());
                activeVotes.put(request.getRoomId(), request);
            } else {
                log.info("[VoteState] Stopping vote tracking for room: {}", request.getRoomId());
                activeVotes.remove(request.getRoomId());
            }
        } catch (Exception e) {
            log.error("[VoteState] Failed to process vote event", e);
        }
    }

    public boolean isVoteActive(String roomId) {
        return activeVotes.containsKey(roomId);
    }

    public VoteRequest getVoteRequest(String roomId) {
        return activeVotes.get(roomId);
    }
}
