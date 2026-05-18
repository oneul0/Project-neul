package com.gak.analyzer.llm;

import reactor.core.publisher.Mono;

/**
 * LLM 프로바이더 교체를 위한 추상화 계층.
 * 구현체를 교체하면 Ollama → OpenAI → Claude 등 변경이 가능하다.
 */
public interface ChatLlmClient {
    /**
     * @return LLM이 반환한 raw text content. 빈 문자열이면 호출 측에서 fallback 처리.
     */
    Mono<String> chat(String systemPrompt, String userPrompt, double temperature, int numPredict);
}
