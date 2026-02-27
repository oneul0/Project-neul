package com.neul.core_api.domain.chat.service;

import com.neul.core_api.domain.chat.dto.AnalyzedChatMessage;
import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import com.neul.core_api.domain.chat.repository.AnalyzedChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final AnalyzedChatRepository analyzedChatRepository;
    private final StreamRedisService streamRedisService;

    // Room 별로 SSE 스트림을 관리하기 위한 Sink 맵
    // 실제 운영 환경에서는 분산 처리를 위해 Redis Pub/Sub을 사용해야 하지만,
    // 현재는 단일 인스턴스 개발/테스트용으로 ConcurrentHashMap + Sinks.Many를 사용합니다.
    // replay(100): 최근 100개 이벤트를 버퍼링하여 구독 시점 이전 데이터도 수신 가능
    private final Map<String, Sinks.Many<Object>> roomSinks = new ConcurrentHashMap<>();

    @KafkaListener(topics = "analyzed-chat-topic", groupId = "neul-core-api-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeAnalyzedChat(AnalyzedChatMessage message) {
        log.info("[Kafka] Consumed analyzed chat: roomId={}, content={}, emotion={}",
                message.getRoomId(), message.getContent(), message.getEmotion().getType());

        String roomId = message.getRoomId();
        
        // 1. R2DBC 비동기 저장 (PostgreSQL)
        AnalyzedChat entity = AnalyzedChat.builder()
                .messageId(message.getMessageId())
                .roomId(message.getRoomId())
                .content(message.getContent())
                .emotionType(message.getEmotion().getType())
                .emotionScore(message.getEmotion().getScore())
                .analyzedAt(message.getAnalyzedAt())
                .build();
                
        analyzedChatRepository.save(entity)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        saved -> log.debug("Saved to DB: {}", saved.getId()),
                        error -> log.error("DB Save Error: {}", error.getMessage())
                );

        // 2. Redis 실시간 지표 누적 업데이트 및 SSE 브로드캐스팅
        streamRedisService.incrementEmotionStats(roomId, message.getEmotion().getType())
                .then(streamRedisService.getRoomStats(roomId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        stats -> {
                            // SSE를 위한 Sink 획득 (없으면 생성 안함 - 구독자가 있을 때만)
                            Sinks.Many<Object> sink = roomSinks.get(roomId);
                            if (sink != null) {
                                // chat_analyzed 이벤트 푸시 (개별 메시지)
                                sink.tryEmitNext(Map.of("event", "chat_analyzed", "data", message));
                                // stats_update 이벤트 푸시 (전체 통계)
                                sink.tryEmitNext(Map.of("event", "stats_update", "data", stats));
                            }
                        },
                        error -> log.error("Redis Update Error: {}", error.getMessage())
                );
    }

    // SSE 구독 요청 시 Flux 반환
    public Flux<Object> subscribeRoom(String roomId) {
        log.info("Client subscribed to SSE stream for room: {}", roomId);
        
        Sinks.Many<Object> sink = roomSinks.computeIfAbsent(roomId,
                key -> Sinks.many().replay().limit(100));

        return sink.asFlux()
                .doOnCancel(() -> log.info("Client unsubscribed from room: {}", roomId))
                .doFinally(signalType -> {
                    // 구독자가 0명이 되면 메모리 릭 방지를 위해 sink 제거 로직 등 추가 고려
                    if (sink.currentSubscriberCount() == 0) {
                        roomSinks.remove(roomId);
                        log.info("Removed unused sink for room: {}", roomId);
                    }
                });
    }
}
