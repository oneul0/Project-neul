package com.gak.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ollama.prompts")
public class OllamaPromptProperties {

    private String sentimentSystem = "classpath:prompts/ollama-sentiment-system.txt";
    private String sentimentUser = "classpath:prompts/ollama-sentiment-user.txt";
    private String highlightSystem = "classpath:prompts/ollama-highlight-system.txt";
    private String highlightUser = "classpath:prompts/ollama-highlight-user.txt";

    public String getSentimentSystem() {
        return sentimentSystem;
    }

    public void setSentimentSystem(String sentimentSystem) {
        this.sentimentSystem = sentimentSystem;
    }

    public String getSentimentUser() {
        return sentimentUser;
    }

    public void setSentimentUser(String sentimentUser) {
        this.sentimentUser = sentimentUser;
    }

    public String getHighlightSystem() {
        return highlightSystem;
    }

    public void setHighlightSystem(String highlightSystem) {
        this.highlightSystem = highlightSystem;
    }

    public String getHighlightUser() {
        return highlightUser;
    }

    public void setHighlightUser(String highlightUser) {
        this.highlightUser = highlightUser;
    }
}
