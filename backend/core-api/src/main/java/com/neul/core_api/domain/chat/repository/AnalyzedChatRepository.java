package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.AnalyzedChat;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyzedChatRepository extends ReactiveCrudRepository<AnalyzedChat, Long> {
    reactor.core.publisher.Flux<AnalyzedChat> findByRoomIdAndSenderId(String roomId, String senderId);
    reactor.core.publisher.Mono<AnalyzedChat> findByMessageId(String messageId);
}
