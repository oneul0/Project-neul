package com.neul.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neul.common.dto.VodCrawlCompletedEvent;
import com.neul.common.dto.VodHighlightPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VodHighlightAnalyzerTest {

    private ObjectMapper objectMapper;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private OllamaAnalyzerService ollamaAnalyzerService;

    private VodHighlightAnalyzer vodHighlightAnalyzer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        vodHighlightAnalyzer = new VodHighlightAnalyzer(objectMapper, kafkaTemplate, ollamaAnalyzerService);
    }

    @Test
    @DisplayName("LLM이 알 수 없는 카테고리를 반환해도 게시 하이라이트는 정규화된 카테고리와 라벨을 사용한다")
    void consumeCompletion_NormalizesUnknownEditorialCategory() throws Exception {
        String videoNo = "video-123";
        when(ollamaAnalyzerService.analyzeHighlight(any()))
                .thenReturn(Mono.just(new HighlightDecision(true, "알수없음", "LLM 요약", 8, "LLM 근거")));

        vodHighlightAnalyzer.consumeVodChunks(buildChatChunkJson(), videoNo);

        String completionJson = objectMapper.writeValueAsString(VodCrawlCompletedEvent.builder()
                .videoNo(videoNo)
                .pagesProcessed(1)
                .chatsCollected(30)
                .build());

        vodHighlightAnalyzer.consumeCompletion(completionJson, videoNo);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeast(5)).send(eq("vod-analyzed-topic"), eq(videoNo), payloadCaptor.capture());

        List<VodHighlightPoint> highlightPoints = new ArrayList<>();
        for (String payload : payloadCaptor.getAllValues()) {
            highlightPoints.add(objectMapper.readValue(payload, VodHighlightPoint.class));
        }

        assertThat(highlightPoints).hasSizeGreaterThanOrEqualTo(5);
        assertThat(highlightPoints)
                .allSatisfy(point -> {
                    assertThat(point.getCategory()).isEqualTo("소통");
                    assertThat(point.getReactionLabel()).isEqualTo("소통");
                    assertThat(point.getDescription()).isEqualTo("LLM 요약");
                    assertThat(point.getReasonSummary()).contains("LLM 근거");
                });
    }

    @Test
    @DisplayName("LLM이 하이라이트가 아니라고 판정한 후보는 배포 후보와 최소 개수 보정에서 제외한다")
    void consumeCompletion_ExcludesRejectedHighlights() throws Exception {
        String videoNo = "video-reject";
        when(ollamaAnalyzerService.analyzeHighlight(any()))
                .thenReturn(Mono.just(new HighlightDecision(false, "판단보류", "제외", 2, "하이라이트 아님")));

        vodHighlightAnalyzer.consumeVodChunks(buildChatChunkJson(), videoNo);

        String completionJson = objectMapper.writeValueAsString(VodCrawlCompletedEvent.builder()
                .videoNo(videoNo)
                .pagesProcessed(1)
                .chatsCollected(30)
                .build());

        vodHighlightAnalyzer.consumeCompletion(completionJson, videoNo);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("vod-analysis-complete-topic"), eq(videoNo), payloadCaptor.capture());

        Map<String, Object> completionEvent = objectMapper.readValue(payloadCaptor.getValue(), Map.class);
        assertThat(completionEvent.get("highlightsCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("LLM 리뷰 배치가 타임아웃돼도 휴리스틱 결과로 완료 이벤트를 발행한다")
    void consumeCompletion_LlmReviewTimeoutFallsBackToHeuristics() throws Exception {
        String videoNo = "video-timeout";
        when(ollamaAnalyzerService.analyzeHighlight(any()))
                .thenReturn(Mono.error(new IllegalStateException(new TimeoutException("simulated timeout"))));

        vodHighlightAnalyzer.consumeVodChunks(buildChatChunkJson(), videoNo);

        String completionJson = objectMapper.writeValueAsString(VodCrawlCompletedEvent.builder()
                .videoNo(videoNo)
                .pagesProcessed(1)
                .chatsCollected(30)
                .build());

        vodHighlightAnalyzer.consumeCompletion(completionJson, videoNo);

        ArgumentCaptor<String> highlightCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeast(5)).send(eq("vod-analyzed-topic"), eq(videoNo), highlightCaptor.capture());

        List<VodHighlightPoint> highlightPoints = new ArrayList<>();
        for (String payload : highlightCaptor.getAllValues()) {
            highlightPoints.add(objectMapper.readValue(payload, VodHighlightPoint.class));
        }

        assertThat(highlightPoints).hasSizeGreaterThanOrEqualTo(5);
        assertThat(highlightPoints)
                .allSatisfy(point -> assertThat(point.getCategory()).isIn("LAUGH", "WONDER", "HYPE", "TENSION", "HOT_MOMENT"));
    }

    private String buildChatChunkJson() throws Exception {
        List<Map<String, Object>> chats = new ArrayList<>();
        for (int window = 0; window < 5; window++) {
            int startSeconds = window * 30;
            for (int index = 0; index < 6; index++) {
                Map<String, Object> chat = new LinkedHashMap<>();
                chat.put("videoInSeconds", startSeconds + index);
                chat.put("userIdHash", "user-" + window + "-" + index);
                chat.put("message", "와 미쳤다 한타 " + window + " " + index + "!!");
                chats.add(chat);
            }
        }
        return objectMapper.writeValueAsString(chats);
    }
}
