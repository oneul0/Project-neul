package com.gak.analyzer.dto.ollama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OllamaResponse {
    private String model;
    private String createdAt;
    private OllamaMessage message;
    private boolean done;
}
