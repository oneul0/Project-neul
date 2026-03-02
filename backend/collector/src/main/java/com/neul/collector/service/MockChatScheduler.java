package com.neul.collector.service;

import com.neul.collector.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockChatScheduler {

    private final ChatProducer chatProducer;
    private final List<String> activeRooms = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private static final String[] MOCK_MESSAGES = {
            "안녕하세요!", "오늘 방송 재밌네요", "ㅋㅋ", "아쉽다", "오오",
            "대박", "질문있습니다", "화이팅!", "잘 보고 있습니다", "이거 어떻게 하는거죠?"
    };

    private static final String[] MOCK_SENDERS = {
            "userA", "userB", "tester", "oneul", "viewer123",
            "hello_world", "dev_guy", "guest", "admin", "neul_fan"
    };

    public void startBroadcast(String roomId) {
        if (!activeRooms.contains(roomId)) {
            activeRooms.add(roomId);
            log.info("Started mock broadcast for roomId: {}", roomId);
        }
    }
    
    public void stopBroadcast(String roomId) {
        activeRooms.remove(roomId);
        log.info("Stopped mock broadcast for roomId: {}", roomId);
    }

    // Schedule to run every 1 second (1000 ms)
    @Scheduled(fixedRate = 1000)
    public void generateMockChats() {
        if (activeRooms.isEmpty()) {
            return;
        }

        for (String roomId : activeRooms) {
            // Generate 10 messages per room per second
            for (int i = 0; i < 10; i++) {
                String sender = MOCK_SENDERS[random.nextInt(MOCK_SENDERS.length)];
                String content = MOCK_MESSAGES[random.nextInt(MOCK_MESSAGES.length)];
                
                RawChatMessage message = RawChatMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .roomId(roomId)
                        .sender(sender)
                        .content(content)
                        .timestamp(LocalDateTime.now())
                        .build();

                chatProducer.sendChat(message);
            }
        }
    }
}
