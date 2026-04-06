package com.neul.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.collector.config.ChzzkProperties;
import com.neul.collector.controller.VodMetadataResponse;
import com.neul.common.dto.VodCrawlCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VodChatCrawlerService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_RETRIES = 2;

    private final WebClient webClient = WebClient.builder().build();
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final VodAnalysisStatusService vodAnalysisStatusService;
    private final ChzzkProperties chzzkProperties;

    public Mono<CrawlProgress> crawlFullVodChat(String videoNo) {
        log.info("[VOD-Crawler] Starting full chat crawl for videoNo={}", videoNo);
        return fetchVideoMetadata(videoNo)
                .onErrorReturn(VodMetadataResponse.notFound(videoNo))
                .flatMap(metadata -> fetchChunksRecursive(videoNo, null, 0, 0, new HashSet<>(), 0)
                        .doOnSuccess(result -> {
                            vodAnalysisStatusService.markAnalyzing(videoNo, result.pagesProcessed(), result.chatsCollected());
                            try {
                                String payload = objectMapper.writeValueAsString(VodCrawlCompletedEvent.builder()
                                        .videoNo(videoNo)
                                        .pagesProcessed(result.pagesProcessed())
                                        .chatsCollected(result.chatsCollected())
                                        .title(metadata.title())
                                        .category(metadata.category())
                                        .duration(metadata.duration())
                                        .build());
                                kafkaTemplate.send("vod-crawl-complete-topic", videoNo, payload);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to publish VOD crawl completion", e);
                            }
                        }));
    }

    public Mono<VodMetadataResponse> fetchVideoMetadata(String videoNo) {
        String uri = chzzkProperties.getVodMetadataUrl().replace("{videoNo}", videoNo);

        return webClient.get()
                .uri(uri)
                .exchangeToMono(response -> {
                    if (response.statusCode().is4xxClientError()) {
                        return Mono.just(VodMetadataResponse.notFound(videoNo));
                    }

                    return response.bodyToMono(String.class)
                            .flatMap(json -> {
                                try {
                                    JsonNode root = objectMapper.readTree(json);
                                    JsonNode content = root.path("content");
                                    if (content.isMissingNode() || content.isNull() || content.isEmpty()) {
                                        return Mono.just(VodMetadataResponse.notFound(videoNo));
                                    }

                                    return Mono.just(new VodMetadataResponse(
                                            true,
                                            videoNo,
                                            content.path("videoTitle").asText(null),
                                            content.path("thumbnailImageUrl").asText(null),
                                            content.path("publishDate").asText(null),
                                            content.path("publishDateAt").isNumber() ? content.path("publishDateAt").asLong() : null,
                                            content.path("channel").path("channelName").asText(null),
                                            content.path("duration").isNumber() ? content.path("duration").asInt() : null,
                                            extractCategory(content),
                                            null
                                    ));
                                } catch (Exception e) {
                                    log.error("[VOD-Crawler] Failed to parse video metadata for videoNo={}", videoNo, e);
                                    return Mono.just(new VodMetadataResponse(false, videoNo, null, null, null, null, null, null, null, "VOD 정보를 해석하지 못했습니다."));
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.error("[VOD-Crawler] Failed to fetch video metadata for videoNo={}", videoNo, e);
                    return Mono.just(new VodMetadataResponse(false, videoNo, null, null, null, null, null, null, null, "VOD 정보를 불러오지 못했습니다."));
                });
    }

    private String extractCategory(JsonNode content) {
        return firstNonBlank(
                content.path("videoCategoryValue"),
                content.path("liveCategoryValue"),
                content.path("categoryValue"),
                content.path("category").path("categoryValue"),
                content.path("category").path("name"),
                content.path("game").path("gameName"),
                content.path("game").path("name")
        );
    }

    private String firstNonBlank(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String value = node.asText(null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private Mono<CrawlProgress> fetchChunksRecursive(
            String videoNo,
            String cursor,
            int pagesProcessed,
            int chatsCollected,
            Set<String> visitedCursors,
            int retryCount
    ) {
        String uri = chzzkProperties.getVodChatUrl().replace("{videoNo}", videoNo) + "?playerType=VOD";
        if (cursor != null && !cursor.isBlank()) {
            uri += "&playerMessageTime=" + cursor;
        }

        String currentCursor = cursor == null ? "INITIAL" : cursor;
        if (retryCount == 0 && !visitedCursors.add(currentCursor)) {
            log.warn("[VOD-Crawler] Detected repeated cursor for videoNo={}, cursor={}. Stopping crawl.", videoNo, currentCursor);
            return Mono.just(new CrawlProgress(pagesProcessed, chatsCollected));
        }

        if (retryCount == 0) {
            vodAnalysisStatusService.markWaiting(
                    videoNo,
                    pagesProcessed,
                    chatsCollected,
                    "다음 채팅 묶음을 요청하고 있습니다."
            );
            log.info("[VOD-Crawler] Requesting chunk videoNo={}, cursor={}, pages={}, chats={}",
                    videoNo, currentCursor, pagesProcessed, chatsCollected);
        } else {
            vodAnalysisStatusService.markWaiting(
                    videoNo,
                    pagesProcessed,
                    chatsCollected,
                    "응답이 지연되어 다시 요청하고 있습니다."
            );
            log.warn("[VOD-Crawler] Retrying chunk videoNo={}, cursor={}, attempt={}",
                    videoNo, currentCursor, retryCount + 1);
        }

        return webClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            int statusCode = response.statusCode().value();
                            String message = statusCode == 429
                                    ? "CHZZK 서버 제한에 걸려 잠시 대기 후 다시 시도해야 합니다."
                                    : (statusCode == 403
                                    ? "CHZZK가 현재 요청을 허용하지 않았습니다."
                                    : "CHZZK 채팅 응답 요청에 실패했습니다. status=" + statusCode);
                            log.warn("[VOD-Crawler] Upstream error videoNo={}, cursor={}, status={}, body={}",
                                    videoNo, currentCursor, statusCode, body);
                            return Mono.error(new IllegalStateException(message));
                        }))
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .flatMap(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        JsonNode content = root.path("content");
                        JsonNode videoChats = content.path("videoChats");

                        int chunkSize = videoChats.isArray() ? videoChats.size() : 0;
                        int nextPagesProcessed = pagesProcessed + 1;
                        int nextChatsCollected = chatsCollected + chunkSize;

                        if (chunkSize > 0) {
                            String jsonPayload = objectMapper.writeValueAsString(videoChats);
                            kafkaTemplate.send("vod-raw-chat-topic", videoNo, jsonPayload);
                        }

                        vodAnalysisStatusService.markCrawling(videoNo, nextPagesProcessed, nextChatsCollected);

                        String nextCursor = extractNextCursor(content, videoChats, cursor);
                        if (nextPagesProcessed == 1 || nextPagesProcessed % 10 == 0) {
                            log.info("[VOD-Crawler] Progress videoNo={}, pages={}, chats={}, nextCursor={}",
                                    videoNo, nextPagesProcessed, nextChatsCollected, nextCursor);
                        }

                        if (nextCursor != null && !nextCursor.isBlank() && !nextCursor.equals(cursor)) {
                            return fetchChunksRecursive(videoNo, nextCursor, nextPagesProcessed, nextChatsCollected, visitedCursors, 0);
                        }

                        log.info("[VOD-Crawler] Reached end of VOD chats for videoNo={}, pages={}, chats={}",
                                videoNo, nextPagesProcessed, nextChatsCollected);
                        return Mono.just(new CrawlProgress(nextPagesProcessed, nextChatsCollected));
                    } catch (Exception e) {
                        log.error("[VOD-Crawler] Failed to parse VOD chats JSON for videoNo={}", videoNo, e);
                        return Mono.error(e);
                    }
                })
                .onErrorResume(error -> {
                    if (retryCount < MAX_RETRIES) {
                        log.warn("[VOD-Crawler] Chunk request failed videoNo={}, cursor={}, attempt={}, error={}",
                                videoNo, currentCursor, retryCount + 1, error.getMessage());
                        return Mono.delay(Duration.ofSeconds(retryCount + 1L))
                                .flatMap(ignored -> fetchChunksRecursive(
                                        videoNo,
                                        cursor,
                                        pagesProcessed,
                                        chatsCollected,
                                        visitedCursors,
                                        retryCount + 1
                                ));
                    }

                    String message = error.getMessage() == null ? "VOD 채팅 수집 중 오류가 발생했습니다." : error.getMessage();
                    if (message.contains("timed out")) {
                        message = "CHZZK 응답이 지연되어 채팅 수집이 중단되었습니다.";
                    }
                    vodAnalysisStatusService.markFailed(videoNo, message);
                    return Mono.error(error);
                });
    }

    private String extractNextCursor(JsonNode content, JsonNode videoChats, String currentCursor) {
        JsonNode nextPlayerMessageTime = content.path("nextPlayerMessageTime");
        if (nextPlayerMessageTime.isNumber()) {
            long value = nextPlayerMessageTime.asLong();
            if (value > 0) {
                String candidate = String.valueOf(value);
                if (!candidate.equals(currentCursor)) {
                    return candidate;
                }
            }
        }
        if (nextPlayerMessageTime.isTextual()) {
            String value = nextPlayerMessageTime.asText(null);
            if (value != null && !value.isBlank()) {
                if (!value.equals(currentCursor)) {
                    return value;
                }
            }
        }

        JsonNode nextNode = content.path("next");
        if (nextNode.isTextual()) {
            String direct = nextNode.asText(null);
            if (direct != null && !direct.isBlank()) {
                if (!direct.equals(currentCursor)) {
                    return direct;
                }
            }
        }
        if (nextNode.isObject()) {
            String nested = nextNode.path("next").asText(null);
            if (nested != null && !nested.isBlank()) {
                if (!nested.equals(currentCursor)) {
                    return nested;
                }
            }
            String value = nextNode.path("value").asText(null);
            if (value != null && !value.isBlank()) {
                if (!value.equals(currentCursor)) {
                    return value;
                }
            }
        }

        String derived = deriveCursorFromLastChat(videoChats, currentCursor);
        if (derived != null && !derived.isBlank() && !derived.equals(currentCursor)) {
            return derived;
        }

        return null;
    }

    private String deriveCursorFromLastChat(JsonNode videoChats, String currentCursor) {
        if (!videoChats.isArray() || videoChats.isEmpty()) {
            return null;
        }

        JsonNode lastChat = videoChats.get(videoChats.size() - 1);

        long current = parseLongOrDefault(currentCursor, -1L);
        long playerMessageTime = lastChat.path("playerMessageTime").asLong(-1L);
        if (playerMessageTime > current) {
            return String.valueOf(playerMessageTime);
        }

        long videoInSeconds = lastChat.path("videoInSeconds").asLong(-1L);
        if (videoInSeconds >= 0) {
            long derived = (videoInSeconds * 1000L) + 1L;
            if (derived > current) {
                return String.valueOf(derived);
            }
        }

        return null;
    }

    private long parseLongOrDefault(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public record CrawlProgress(int pagesProcessed, int chatsCollected) {
    }
}
