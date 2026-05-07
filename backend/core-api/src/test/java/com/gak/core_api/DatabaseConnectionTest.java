package com.gak.core_api;

import com.gak.core_api.domain.chat.entity.AnalyzedChat;
import com.gak.core_api.domain.chat.repository.AnalyzedChatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

@SpringBootTest
@Disabled("Requires Docker & running Postgres")
class DatabaseConnectionTest {

    @Autowired
    private AnalyzedChatRepository repository;

    @Test
    void testDatabaseConnectivityAndSchema() {
        AnalyzedChat chat = AnalyzedChat.builder()
                .messageId("test-msg-id-" + System.currentTimeMillis())
                .roomId("test-room")
                .content("DB 연결 테스트입니다.")
                .sender("Tester")
                .emotionType("NEUTRAL")
                .emotionScore(0.0)
                .analyzedAt(LocalDateTime.now())
                .build();

        // Save
        StepVerifier.create(repository.save(chat))
                .expectNextMatches(saved -> saved.getId() != null)
                .verifyComplete();

        // Find and Delete
        repository.deleteAll().block();
    }
}
