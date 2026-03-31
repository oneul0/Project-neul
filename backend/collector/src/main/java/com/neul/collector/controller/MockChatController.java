package com.neul.collector.controller;

import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import com.neul.collector.service.ChatProducer;
import com.neul.collector.v2.producer.V2ChatProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/dev")
@RequiredArgsConstructor
public class MockChatController {

    private static final List<String> SAMPLE_MESSAGES = List.of(
            "오늘 방송 텐션 너무 좋다",
            "이 장면 진짜 웃기네요",
            "채팅 반응 좋은데요?",
            "조금 답답한 구간인 것 같아요",
            "와 방금 플레이 미쳤다",
            "이거 다시 보고 싶어요",
            "!1",
            "!2");

    private static final List<String> SAMPLE_SENDERS = List.of(
            "테스트유저1",
            "테스트유저2",
            "테스트유저3",
            "테스트유저4");

    private final ChatProducer chatProducer;
    private final V2ChatProducer v2ChatProducer;

    @PostMapping("/mock-chat/{roomId}")
    public Map<String, Object> injectMockChat(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "8") int count) {

        int safeCount = Math.max(1, Math.min(count, 50));
        LocalDateTime now = LocalDateTime.now();

        List<RawChatMessage> messages = IntStream.range(0, safeCount)
                .mapToObj(index -> RawChatMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .roomId(roomId)
                        .messageType("CHAT")
                        .sender(SAMPLE_SENDERS.get(index % SAMPLE_SENDERS.size()))
                        .senderId("mock-user-" + (index % SAMPLE_SENDERS.size()))
                        .content(SAMPLE_MESSAGES.get(index % SAMPLE_MESSAGES.size()))
                        .timestamp(now.plusSeconds(index))
                        .build())
                .toList();

        chatProducer.sendBatch(RawChatBatch.builder()
                .roomId(roomId)
                .messages(messages)
                .batchTime(now)
                .build());

        messages.forEach(v2ChatProducer::sendRawChat);

        return Map.of(
                "roomId", roomId,
                "count", safeCount,
                "status", "mock_sent");
    }
}
