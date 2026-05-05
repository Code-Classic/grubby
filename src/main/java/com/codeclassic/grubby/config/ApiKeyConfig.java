package com.codeclassic.grubby.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyConfig {

    private final String apiKey;
    private final String claudeApiKey;

    public ApiKeyConfig(
            @Value("${llm.api.key:}") String apiKey,
            @Value("${claude.api.key:}") String claudeApiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.claudeApiKey = claudeApiKey == null ? "" : claudeApiKey.trim();
    }

    public String getApiKey() { return apiKey; }
    public boolean isConfigured() { return !apiKey.isBlank(); }

    public String getClaudeApiKey() { return claudeApiKey; }
    public boolean isClaudeConfigured() { return !claudeApiKey.isBlank(); }
}
