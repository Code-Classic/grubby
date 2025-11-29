package com.codeclassic.grubby.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeclassic.grubby.config.ApiKeyConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal AI service that sends a prompt (repoUrl + feature context) to OpenAI Chat Completions API
 * and returns Markdown text suitable for preview. If no API key is configured, it returns a
 * deterministic stub based on inputs.
 */
@Service
@RequiredArgsConstructor
public class AiProcessingService {

    @org.springframework.beans.factory.annotation.Value("${retry.llm.attempts:3}")
    private int retryAttempts;
    @org.springframework.beans.factory.annotation.Value("${retry.llm.initialDelayMillis:2000}")
    private long retryInitialDelayMs;
    @org.springframework.beans.factory.annotation.Value("${retry.llm.backoff:1.8}")
    private double retryBackoff;
    @org.springframework.beans.factory.annotation.Value("${llm.http.timeout.seconds:60}")
    private int httpTimeoutSeconds;

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);

    private final ApiKeyConfig apiKeyConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateMarkdown(String repoUrl, String featureContext) {
        return generateMarkdown(repoUrl, featureContext, null);
    }

    public String generateMarkdown(String repoUrl, String featureContext, Object summaryOrNull) {
        if (!apiKeyConfig.isConfigured()) {
            // Fallback: simple stub
            String fc = featureContext == null || featureContext.isBlank() ? "(no feature context provided)" : featureContext;
            return "# BRD (Stub)\n\n" +
                    "## Overview & Purpose\n" +
                    "This is a placeholder BRD generated without a configured LLM API key.\n\n" +
                    "## Input Summary\n" +
                    "- Repository: " + repoUrl + "\n" +
                    "- Feature Context: " + fc + "\n\n" +
                    (summaryOrNull != null ? ("- Code Summary (truncated):\n```json\n" + safeJson(summaryOrNull) + "\n```\n\n") : "") +
                    "## Suggested Next Steps\n" +
                    "1. Configure llm.api.key in application.properties.\n" +
                    "2. Re-run generation to receive AI-based content.";
        }

        try {
            String apiKey = apiKeyConfig.getApiKey();
            String prompt = buildPrompt(repoUrl, featureContext, summaryOrNull);
            String body = objectMapper.createObjectNode()
                    .put("model", "gpt-4o-mini")
                    .put("temperature", 0.2)
                    .set("messages", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .put("role", "system")
                                    .put("content", "You are a senior Business Analyst. Produce a concise, well-structured BRD in Markdown with sections: Overview, Existing System Summary (if any), Proposed Changes, Business Rules, APIs to Consider, Risks & Assumptions, and Next Steps."))
                            .add(objectMapper.createObjectNode()
                                    .put("role", "user")
                                    .put("content", prompt))
                    ).toString();

            java.time.Duration timeout = java.time.Duration.ofSeconds(Math.max(10, httpTimeoutSeconds));
            final String apiEndpoint = "https://api.openai.com/v1/chat/completions";

            String result = com.codeclassic.grubby.util.RetryUtils.withRetry(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiEndpoint))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() / 100 != 2) {
                    throw new RuntimeException("LLM non-2xx: status=" + response.statusCode());
                }
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                String content = contentNode.isMissingNode() ? null : contentNode.asText();
                if (content == null || content.isBlank()) {
                    throw new RuntimeException("LLM empty content");
                }
                return content.trim();
            }, retryAttempts, java.time.Duration.ofMillis(Math.max(200L, retryInitialDelayMs)), retryBackoff, ex -> true);
            return result;
        } catch (Exception ex) {
            log.error("LLM call error", ex);
            return fallbackMarkdown(repoUrl, featureContext, "LLM error: " + ex.getClass().getSimpleName());
        }
    }

    private String buildPrompt(String repoUrl, String featureContext) {
        return buildPrompt(repoUrl, featureContext, null);
    }

    private String buildPrompt(String repoUrl, String featureContext, Object summaryOrNull) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository URL: ").append(repoUrl).append('\n');
        if (featureContext != null && !featureContext.isBlank()) {
            sb.append("User Feature/Business Context: ").append(featureContext).append('\n');
        }
        if (summaryOrNull != null) {
            sb.append("\nCode Analysis Summary (JSON, truncated if large):\n");
            sb.append("```json\n").append(safeJson(summaryOrNull)).append("\n```\n");
        }
        sb.append("\nPlease synthesize a BRD in Markdown based on the repository inputs and the code summary. Focus on business intent, current APIs/endpoints (if any), proposed changes related to the feature context, and clear, actionable next steps.");
        return sb.toString();
    }

    private String safeJson(Object obj) {
        try {
            String raw = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            // Limit extremely large payloads
            if (raw.length() > 6000) {
                return raw.substring(0, 6000) + "\n... (truncated)";
            }
            return raw;
        } catch (Exception e) {
            return "{}";
        }
    }

    private String fallbackMarkdown(String repoUrl, String featureContext, String note) {
        String fc = featureContext == null || featureContext.isBlank() ? "(no feature context provided)" : featureContext;
        return "# BRD (Fallback)\n\n" +
                "> Note: " + note + "\n\n" +
                "## Overview & Purpose\n" +
                "Generate BRD for the given repository and feature context.\n\n" +
                "## Input Summary\n" +
                "- Repository: " + repoUrl + "\n" +
                "- Feature Context: " + fc + "\n\n" +
                "## Next Steps\n" +
                "Re-run after resolving the LLM configuration.";
    }
}
