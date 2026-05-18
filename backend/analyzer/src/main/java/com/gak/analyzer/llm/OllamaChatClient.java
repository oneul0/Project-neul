package com.gak.analyzer.llm;

import com.gak.analyzer.dto.ollama.OllamaMessage;
import com.gak.analyzer.dto.ollama.OllamaRequest;
import com.gak.analyzer.dto.ollama.OllamaResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class OllamaChatClient implements ChatLlmClient {

    private final WebClient webClient;

    @Value("${app.ollama.api-url}")
    private String apiUrl;

    @Value("${app.ollama.model}")
    private String model;

    public OllamaChatClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<String> chat(String systemPrompt, String userPrompt, double temperature, int numPredict) {
        OllamaRequest request = OllamaRequest.builder()
                .model(model)
                .messages(List.of(
                        OllamaMessage.builder().role("system").content(systemPrompt).build(),
                        OllamaMessage.builder().role("user").content(userPrompt).build()
                ))
                .stream(false)
                .format("json")
                .options(Map.of(
                        "temperature", temperature,
                        "num_predict", numPredict,
                        "top_p", 0.8
                ))
                .build();

        return webClient.post()
                .uri(apiUrl)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .map(res -> res.getMessage() != null && res.getMessage().getContent() != null
                        ? res.getMessage().getContent()
                        : "");
    }
}
