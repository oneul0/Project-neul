package com.neul.collector.service;

import com.neul.collector.dto.RawChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DummyChatGenerator {

	private final KafkaTemplate<String, RawChatMessage> kafkaTemplate;
	private final Random random = new Random();

	private final List<String> dummyContents = List.of(
		"와 오늘 경기 진짜 대박이다!",
		"방장님 하이요~",
		"이게 실화냐? ㅋㅋㅋ",
		"채팅창 속도 실화임?",
		"오늘따라 화질이 안 좋네요ㅠㅠ",
		"응원합니다 화이팅!!",
		"노잼인데 딴거 하죠"
	);

	@EventListener(ApplicationReadyEvent.class)
	public void startGenerating() {
		Flux.interval(Duration.ofSeconds(1))
			.map(i -> RawChatMessage.builder()
				.messageId(UUID.randomUUID().toString())
				.roomId("test-room-1")
				.sender("user-" + random.nextInt(100))
				.content(dummyContents.get(random.nextInt(dummyContents.size())))
				.timestamp(LocalDateTime.now())
				.build())
			.subscribe(message -> {
				kafkaTemplate.send("raw-chat-topic", message.getRoomId(), message);
				log.info("Sent dummy message: {}", message.getContent());
			});
	}
}