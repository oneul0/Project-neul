package com.neul.analyzer.dto.ollama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OllamaRequest {
    private String model;
    private List<OllamaMessage> messages;
    private boolean stream;
    // For enforcing JSON output in Ollama, we can use "format": "json" (if supported by the model/version)
    private String format;
}
