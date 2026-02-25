package com.neul.analyzer.service;

import com.neul.common.dto.AnalyzedMessage;
import com.neul.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiAnalyzerService {

	private final WebClient.Builder webClientBuilder;
	private final KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${gemini.api.key}")
	private String apiKey;

	@Value("${gemini.api.url}")
	private String apiUrl;

	// Kafka에서 수집된 메시지를 리액티브 스트림으로 연결하는 Sink
	private final Sinks.Many<ChatMessage> chatSink = Sinks.many().multicast().onBackpressureBuffer();

	@KafkaListener(topics = "raw-chat-topic", groupId = "analyzer-group")
	public void consume(ChatMessage message) {
		// 리스너가 받은 메시지를 스트림으로 흘려보냄
		chatSink.tryEmitNext(message);
	}

	@PostConstruct
	public void initAnalysisPipeline() {
		// [핵심 로직] Micro-batching 전략 적용
		// 1초 동안 기다리거나, 메시지가 50개가 쌓이면 리스트로 묶어 처리함
		chatSink.asFlux()
			.bufferTimeout(50, Duration.ofSeconds(1))
			.parallel(5) // 최대 5개 병렬 레일(Rail) 생성
			.runOn(Schedulers.boundedElastic())
			.flatMap(this::analyzeWithGemini)
			.flatMap(Flux::fromIterable) // 리스트를 다시 개별 메시지로 평탄화
			.subscribe(
				analyzed -> {
					kafkaTemplate.send("analyzed-chat-topic", analyzed.getRoomId(), analyzed);
					log.info("분석 완료 전송: [{}] -> {}", analyzed.getContent(), analyzed.getEmotion().getType());
				},
				error -> log.error("분석 파이프라인 에러 발생: ", error)
			);
	}

	private Flux<List<AnalyzedMessage>> analyzeWithGemini(List<ChatMessage> messages) {
		if (messages.isEmpty()) return Flux.empty();

		// Gemini API 전송용 프롬프트 최적화
		String chatList = messages.stream()
			.map(m -> m.getMessageId() + ":" + m.getContent())
			.collect(Collectors.joining("\n"));

		String prompt = "다음은 실시간 채팅 리스트야. 각 메시지의 감정을 POSITIVE, NEGATIVE, NEUTRAL 중 하나로 분류하고, " +
			"강도를 -1.0에서 1.0 사이의 점수로 환산해줘. " +
			"응답은 반드시 다른 설명 없이 JSON 형식으로만 보내줘. 예: [{\"id\": \"uuid\", \"emotion\": \"POSITIVE\", \"score\": 0.8}]\n\n" +
			chatList;

		// Gemini API 호출 (비동기 논블로킹)
		return webClientBuilder.build()
			.post()
			.uri(apiUrl + "?key=" + apiKey)
			.bodyValue(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))))
			.retrieve()
			.bodyToMono(Map.class)
			.map(response -> mapToAnalyzedMessages(response, messages))
			.flux();
	}

	private List<AnalyzedMessage> mapToAnalyzedMessages(Map response, List<ChatMessage> originalMessages) {
		log.info("Gemini API 응답 수신 (Batch Size: {})", originalMessages.size());

		// TODO: 실제 프로젝트에서는 response 내의 candidates -> content -> parts -> text를 파싱하여 JSON 변환 필요
		// 아래는 빌더 패턴을 정확하게 사용한 객체 생성 예시입니다.
		return originalMessages.stream().map(m -> {
			// Emotion 내부 정적 클래스에도 @Builder가 있어야 아래와 같이 사용 가능합니다.
			AnalyzedMessage.Emotion emotionResult = AnalyzedMessage.Emotion.builder()
				.type("NEUTRAL")
				.score(0.0)
				.build();

			return AnalyzedMessage.builder()
				.messageId(m.getMessageId())
				.roomId(m.getRoomId())
				.content(m.getContent())
				.emotion(emotionResult)
				.analyzedAt(LocalDateTime.now())
				.build();
		}).collect(Collectors.toList());
	}
}