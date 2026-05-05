package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyzedChatRepository extends ReactiveCrudRepository<AnalyzedChat, Long> {
    reactor.core.publisher.Flux<AnalyzedChat> findByRoomIdAndSenderId(String roomId, String senderId);
    reactor.core.publisher.Flux<AnalyzedChat> findByRoomIdAndSender(String roomId, String sender);
    reactor.core.publisher.Mono<AnalyzedChat> findByMessageId(String messageId);

    @org.springframework.data.r2dbc.repository.Query("SELECT * FROM analyzed_chats WHERE room_id = :roomId ORDER BY analyzed_at DESC LIMIT 200")
    reactor.core.publisher.Flux<AnalyzedChat> findRecentByRoomId(String roomId);
}
