package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.VodHighlightPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * VOD 채팅 데이터를 분석하여 하이라이트 구간을 추출하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VodHighlightAnalyzer {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "vod-raw-chat-topic", groupId = "neul-analyzer-vod-group", containerFactory = "vodKafkaListenerContainerFactory")
    public void consumeVodChunks(String json, @Header(KafkaHeaders.RECEIVED_KEY) String videoNo) {
        log.info("[Vod-Analyzer] Processing VOD chunk for videoNo: {}", videoNo);
        try {
            JsonNode chats = objectMapper.readTree(json);
            if (!chats.isArray()) return;

            // 1. 30초 단위 윈도우로 채팅 그룹화 (시간축 분석)
            Map<Integer, List<JsonNode>> windows = new TreeMap<>(); // 시간순 정렬 위해 TreeMap 사용
            for (JsonNode chat : chats) {
                int seconds = chat.path("videoInSeconds").asInt();
                int windowKey = (seconds / 30) * 30;
                windows.computeIfAbsent(windowKey, k -> new ArrayList<>()).add(chat);
            }

            // 2. 윈도우별 하이라이트 점수 계산 및 필터링
            for (Map.Entry<Integer, List<JsonNode>> entry : windows.entrySet()) {
                int startSec = entry.getKey();
                List<JsonNode> windowChats = entry.getValue();
                
                double score = calculateHighlightScore(windowChats);
                
                // 점수가 일정 임계값(예: 10.0)을 넘는 경우만 하이라이트로 간주
                if (score >= 10.0) {
                    String topMsg = findTopMessage(windowChats);
                    String category = determineCategory(windowChats, topMsg);

                    VodHighlightPoint point = VodHighlightPoint.builder()
                            .videoNo(videoNo)
                            .startSeconds(startSec)
                            .endSeconds(startSec + 30)
                            .highlightScore(score)
                            .category(category)
                            .description(generateDescription(category, score))
                            .topMessage(topMsg)
                            .build();

                    kafkaTemplate.send("vod-analyzed-topic", videoNo, objectMapper.writeValueAsString(point));
                    log.info("[Vod-Analyzer] Found highlight: videoNo={}, time={}s, score={}, category={}", 
                            videoNo, startSec, String.format("%.2f", score), category);
                }
            }

        } catch (Exception e) {
            log.error("[Vod-Analyzer] Failed to process VOD chunk", e);
        }
    }

    private double calculateHighlightScore(List<JsonNode> chats) {
        // 기본 점수: 채팅 밀도 (채팅 개수)
        double densityScore = chats.size();
        
        // 가중치 점수: 'ㅋ' 또는 '?' 포함 여부
        long laughCount = chats.stream()
                .filter(c -> {
                    String msg = c.path("message").asText("");
                    return msg.contains("ㅋ") || msg.contains("ㅎ") || msg.contains("LUL") || msg.contains("Grass");
                })
                .count();

        long surpriseCount = chats.stream()
                .filter(c -> {
                    String msg = c.path("message").asText("");
                    return msg.contains("?") || msg.contains("!") || msg.contains("ㄷㄷ") || msg.contains("왓");
                })
                .count();

        return (densityScore * 0.5) + (laughCount * 1.5) + (surpriseCount * 2.0);
    }

    private String determineCategory(List<JsonNode> chats, String topMsg) {
        long laugh = chats.stream().filter(c -> c.path("message").asText("").contains("ㅋ")).count();
        long surprise = chats.stream().filter(c -> c.path("message").asText("").contains("?")).count();

        if (laugh > surprise && laugh > 5) return "LAUGH";
        if (surprise > laugh && surprise > 3) return "WONDER";
        return "HOT_MOMENT";
    }

    private String findTopMessage(List<JsonNode> chats) {
        // 가장 긴 메시지 또는 첫 번째 메시지 (단순화)
        return chats.stream()
                .map(c -> c.path("message").asText(""))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    private String generateDescription(String category, double score) {
        switch (category) {
            case "LAUGH": return "시청자들이 박장대소한 구간 (점수: " + String.format("%.1f", score) + ")";
            case "WONDER": return "놀라운 반응이 쏟아진 구간 (점수: " + String.format("%.1f", score) + ")";
            default: return "채팅 화력이 집중된 구간 (점수: " + String.format("%.1f", score) + ")";
        }
    }
}
