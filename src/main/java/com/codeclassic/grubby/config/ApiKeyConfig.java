package com.codeclassic.grubby.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple configuration component to expose the LLM API key from application.properties.
 * Expected property: llm.api.key=<YOUR_OPENAI_API_KEY>
 */
@Component
public class ApiKeyConfig {

    private final String apiKey;

    public ApiKeyConfig(@Value("${llm.api.key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
