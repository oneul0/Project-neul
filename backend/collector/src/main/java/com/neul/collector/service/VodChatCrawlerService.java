package com.neul.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


/**
 * 치지직 VOD 채팅을 전수 수집하여 Kafka로 전송하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VodChatCrawlerService {

    private final WebClient webClient = WebClient.builder().build();
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String CHZZK_VOD_CHAT_URL = "https://api.chzzk.naver.com/service/v1/videos/{videoNo}/chats";

    public Mono<Void> crawlFullVodChat(String videoNo) {
        log.info("[VOD-Crawler] Starting full chat crawl for videoNo: {}", videoNo);
        return fetchChunksRecursive(videoNo, null);
    }

    private Mono<Void> fetchChunksRecursive(String videoNo, String nextToken) {
        String uri = CHZZK_VOD_CHAT_URL.replace("{videoNo}", videoNo) + "?playerType=VOD";
        if (nextToken != null) {
            uri += "&next=" + nextToken;
        }

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(json -> {
                    log.info("[VOD-Crawler] Received chunk JSON: {}...", json.substring(0, Math.min(json.length(), 200)));
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        JsonNode content = root.path("content");
                        JsonNode videoChats = content.path("videoChats");

                        // 1. Raw Chat 데이터 Kafka 전송 (데이터가 있을 때만)
                        if (videoChats.isArray() && !videoChats.isEmpty()) {
                            try {
                                String jsonPayload = objectMapper.writeValueAsString(videoChats);
                                kafkaTemplate.send("vod-raw-chat-topic", videoNo, jsonPayload);
                                log.debug("[VOD-Crawler] Sent chunk to Kafka: size={}", videoChats.size());
                            } catch (Exception e) {
                                log.error("[VOD-Crawler] Failed to serialize VOD chats", e);
                            }
                        }

                        // 2. 다음 페이지 토큰 확인
                        JsonNode next = content.path("next");
                        String nextPagingToken = next.path("next").asText(null);

                        if (nextPagingToken != null && !nextPagingToken.isBlank()) {
                            return fetchChunksRecursive(videoNo, nextPagingToken);
                        } else {
                            log.info("[VOD-Crawler] Reached end of VOD chats for videoNo: {}", videoNo);
                            return Mono.empty();
                        }
                    } catch (Exception e) {
                        log.error("[VOD-Crawler] Failed to parse VOD chats JSON", e);
                        return Mono.empty();
                    }
                });
    }
}
