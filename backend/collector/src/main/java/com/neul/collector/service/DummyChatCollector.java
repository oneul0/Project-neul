package com.neul.collector.service;

import com.neul.common.dto.RawChatBatch;
import com.neul.common.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트 및 데모용 더미 채팅 수집기.
 * - 실제 소켓 연결 없이 1초마다 가짜 채팅을 생성하여 Kafka로 발행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DummyChatCollector implements ChatCollector {

    private final ChatProducer chatProducer;
    private final Set<String> activeRooms = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();

    private final List<String> dummyContents = List.of(
            "와 오늘 폼 미쳤다 ㅋㅋㅋ",
            "이게 실화냐?? 대박이다 진짜",
            "아... 이건 좀 아닌듯 ㅠㅠ",
            "나만 불편해? 나만 불편하냐고",
            "오오오오 드디어!! 기다리고 있었다구",
            "역시 갓스트리머 ㄷㄷㄷ",
            "채팅창 화력 실화냐? ㅋㅋㅋㅋ",
            "오늘 컨셉 무엇? ㅋㅋㅋ",
            "항상 응원합니다! 파이팅!",
            "ㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋ",
            "ㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠㅠ",
            "?????????????????????????",
            "오늘 날씨만큼이나 상쾌한 방송이네요",
            "이건 좀 에바임;; 선 넘네",
            "지갑 열리는 소리 들린다 ㅋㅋㅋ"
    );

    private final List<String> userNicknames = List.of("치지직고수", "중간만가자", "네온사인", "구름한점", "새벽감성", "코딩왕", "익명의시청자");

    @Override
    public Mono<Void> subscribe(String channelId) {
        activeRooms.add(channelId);
        log.info("[DummyCollector] Started generating dummy chats for room: {}", channelId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> unsubscribe(String channelId) {
        activeRooms.remove(channelId);
        log.info("[DummyCollector] Stopped generating dummy chats for room: {}", channelId);
        return Mono.empty();
    }

    @Override
    public boolean isSubscribed(String channelId) {
        return activeRooms.contains(channelId);
    }

    /**
     * 1초마다 활성화된 방들에 더미 채팅 생성
     */
    @Scheduled(fixedRate = 1000)
    public void generateDummyChats() {
        if (activeRooms.isEmpty()) return;

        for (String roomId : activeRooms) {
            int count = random.nextInt(3) + 1; // 한 번에 1~3개 생성
            for (int i = 0; i < count; i++) {
                RawChatMessage dummy = RawChatMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .roomId(roomId)
                        .messageType("CHAT")
                        .sender(userNicknames.get(random.nextInt(userNicknames.size())))
                        .content(dummyContents.get(random.nextInt(dummyContents.size())))
                        .userRoleCode("common_user")
                        .timestamp(LocalDateTime.now())
                        .build();
                
                chatProducer.sendBatch(RawChatBatch.builder()
                        .roomId(roomId)
                        .messages(List.of(dummy))
                        .batchTime(LocalDateTime.now())
                        .build());
            }
        }
    }
}
