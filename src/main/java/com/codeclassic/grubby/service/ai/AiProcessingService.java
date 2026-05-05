package com.codeclassic.grubby.service.ai;

import com.codeclassic.grubby.config.ApiKeyConfig;
import com.codeclassic.grubby.util.RetryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);
    private static final int MAX_SUMMARY_CHARS = 5_000;

    @Value("${retry.llm.attempts:3}")
    private int retryAttempts;

    @Value("${retry.llm.initialDelayMillis:2000}")
    private long retryInitialDelayMs;

    @Value("${retry.llm.backoff:1.8}")
    private double retryBackoff;

    @Value("${llm.http.timeout.seconds:60}")
    private int httpTimeoutSeconds;

    // OpenAI config
    @Value("${llm.model:gpt-4o-mini}")
    private String defaultOpenAiModel;

    @Value("${llm.temperature:0.2}")
    private double llmTemperature;

    @Value("${llm.api.endpoint:https://api.openai.com/v1/chat/completions}")
    private String openAiEndpoint;

    // Claude config
    @Value("${claude.api.endpoint:https://api.anthropic.com/v1/messages}")
    private String claudeEndpoint;

    @Value("${claude.api.version:2023-06-01}")
    private String claudeApiVersion;

    @Value("${claude.max.tokens:4096}")
    private int claudeMaxTokens;

    private final ApiKeyConfig apiKeyConfig;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public String generateMarkdown(String repoUrl, String featureContext) {
        return generateMarkdown(repoUrl, featureContext, null, null);
    }

    public String generateMarkdown(String repoUrl, String featureContext, Object codeSummary) {
        return generateMarkdown(repoUrl, featureContext, codeSummary, null);
    }

    public String generateMarkdown(String repoUrl, String featureContext, Object codeSummary, String aiModel) {
        String model = resolveModel(aiModel);
        boolean isClaude = isClaude(model);

        if (isClaude && !apiKeyConfig.isClaudeConfigured()) {
            log.warn("Claude API key not configured — returning stub BRD");
            return buildStubMarkdown(repoUrl, featureContext, codeSummary);
        }
        if (!isClaude && !apiKeyConfig.isConfigured()) {
            log.warn("LLM API key not configured — returning stub BRD");
            return buildStubMarkdown(repoUrl, featureContext, codeSummary);
        }

        try {
            String prompt = buildPrompt(repoUrl, featureContext, codeSummary);
            String requestBody = isClaude
                    ? buildClaudeRequestBody(model, prompt)
                    : buildOpenAiRequestBody(model, prompt);
            Duration timeout = Duration.ofSeconds(Math.max(10, httpTimeoutSeconds));

            return RetryUtils.withRetry(
                    () -> isClaude ? callClaude(requestBody, timeout) : callOpenAi(requestBody, timeout),
                    retryAttempts,
                    Duration.ofMillis(Math.max(200L, retryInitialDelayMs)),
                    retryBackoff,
                    ex -> true);

        } catch (Exception ex) {
            log.error("LLM call failed after retries: {}", ex.getClass().getSimpleName());
            throw new RuntimeException("AI generation failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * Generic LLM call with caller-supplied system prompt and user message.
     * Used by the timeline generator and any other non-BRD AI tasks.
     * Falls back to the default model if aiModel is null/blank.
     */
    public String callLlmRaw(String systemPrompt, String userMessage, String aiModel) {
        String model = resolveModel(aiModel);
        boolean isClaude = isClaude(model);

        if (isClaude && !apiKeyConfig.isClaudeConfigured()) {
            throw new RuntimeException("Claude API key not configured");
        }
        if (!isClaude && !apiKeyConfig.isConfigured()) {
            throw new RuntimeException("LLM API key not configured");
        }

        try {
            String requestBody = isClaude
                    ? buildClaudeRawRequestBody(model, systemPrompt, userMessage)
                    : buildOpenAiRawRequestBody(model, systemPrompt, userMessage);
            Duration timeout = Duration.ofSeconds(Math.max(10, httpTimeoutSeconds));

            return RetryUtils.withRetry(
                    () -> isClaude ? callClaude(requestBody, timeout) : callOpenAi(requestBody, timeout),
                    retryAttempts,
                    Duration.ofMillis(Math.max(200L, retryInitialDelayMs)),
                    retryBackoff,
                    ex -> true);
        } catch (Exception ex) {
            log.error("LLM raw call failed after retries: {}", ex.getClass().getSimpleName());
            throw new RuntimeException("AI call failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private String buildOpenAiRawRequestBody(String model, String systemPrompt, String userMessage) throws Exception {
        return objectMapper.createObjectNode()
                .put("model", model)
                .put("temperature", llmTemperature)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "system").put("content", systemPrompt))
                        .add(objectMapper.createObjectNode()
                                .put("role", "user").put("content", userMessage)))
                .toString();
    }

    private String buildClaudeRawRequestBody(String model, String systemPrompt, String userMessage) throws Exception {
        return objectMapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", claudeMaxTokens)
                .put("system", systemPrompt)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "user").put("content", userMessage)))
                .toString();
    }

    public String refineMarkdown(String currentMarkdown, String userPrompt) {
        return refineMarkdown(currentMarkdown, userPrompt, null);
    }

    public String refineMarkdown(String currentMarkdown, String userPrompt, String aiModel) {
        String model = resolveModel(aiModel);
        boolean isClaude = isClaude(model);

        if (isClaude && !apiKeyConfig.isClaudeConfigured()) {
            throw new RuntimeException("Claude API key not configured — cannot refine");
        }
        if (!isClaude && !apiKeyConfig.isConfigured()) {
            throw new RuntimeException("LLM API key not configured — cannot refine");
        }

        try {
            String requestBody = isClaude
                    ? buildClaudeRefineRequestBody(model, currentMarkdown, userPrompt)
                    : buildOpenAiRefineRequestBody(currentMarkdown, userPrompt);
            Duration timeout = Duration.ofSeconds(Math.max(10, httpTimeoutSeconds));

            return RetryUtils.withRetry(
                    () -> isClaude ? callClaude(requestBody, timeout) : callOpenAi(requestBody, timeout),
                    retryAttempts,
                    Duration.ofMillis(Math.max(200L, retryInitialDelayMs)),
                    retryBackoff,
                    ex -> true);

        } catch (Exception ex) {
            log.error("LLM refinement failed after retries: {}", ex.getClass().getSimpleName());
            throw new RuntimeException("AI refinement failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    // ── Provider dispatch helpers ─────────────────────────────────────────────

    private String resolveModel(String aiModel) {
        return (aiModel != null && !aiModel.isBlank()) ? aiModel.trim() : defaultOpenAiModel;
    }

    private boolean isClaude(String model) {
        return model.startsWith("claude-");
    }

    // ── OpenAI call ───────────────────────────────────────────────────────────

    private String callOpenAi(String requestBody, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiEndpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKeyConfig.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() / 100 != 2) {
            log.warn("OpenAI returned non-2xx status: {}", response.statusCode());
            throw new RuntimeException("OpenAI non-2xx: status=" + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        String content = contentNode.isMissingNode() ? null : contentNode.asText();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("OpenAI returned empty content");
        }
        return content.trim();
    }

    private String buildOpenAiRequestBody(String model, String prompt) throws Exception {
        return objectMapper.createObjectNode()
                .put("model", model)
                .put("temperature", llmTemperature)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "system")
                                .put("content",
                                        "You are a senior Business Analyst. Produce a concise, well-structured BRD in Markdown " +
                                        "with these sections: Overview & Purpose, Existing System Summary, Proposed Changes, " +
                                        "Business Rules, APIs to Consider, Risks & Assumptions, and Next Steps. " +
                                        "Be specific, actionable, and reference actual endpoints or classes where relevant."))
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", prompt)))
                .toString();
    }

    private String buildOpenAiRefineRequestBody(String currentMarkdown, String userPrompt) throws Exception {
        String truncated = currentMarkdown.length() > 12_000
                ? currentMarkdown.substring(0, 12_000) + "\n… (truncated)"
                : currentMarkdown;
        return objectMapper.createObjectNode()
                .put("model", defaultOpenAiModel)
                .put("temperature", llmTemperature)
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "system")
                                .put("content",
                                        "You are a senior Business Analyst editing an existing BRD. " +
                                        "Apply the user's instruction to the BRD and return the complete revised document " +
                                        "in Markdown. Keep the same structure (Overview & Purpose, Existing System Summary, " +
                                        "Proposed Changes, Business Rules, APIs to Consider, Risks & Assumptions, Next Steps) " +
                                        "unless the instruction explicitly requires changes to it. " +
                                        "Return only the full revised Markdown — no preamble, no explanation."))
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", "Existing BRD:\n\n" + truncated + "\n\n---\n\nInstruction: " + userPrompt)))
                .toString();
    }

    // ── Claude call ───────────────────────────────────────────────────────────

    private String callClaude(String requestBody, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(claudeEndpoint))
                .timeout(timeout)
                .header("x-api-key", apiKeyConfig.getClaudeApiKey())
                .header("anthropic-version", claudeApiVersion)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() / 100 != 2) {
            log.warn("Claude API returned non-2xx status: {}", response.statusCode());
            throw new RuntimeException("Claude non-2xx: status=" + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.path("content").path(0).path("text");
        String content = contentNode.isMissingNode() ? null : contentNode.asText();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("Claude returned empty content");
        }
        return content.trim();
    }

    private String buildClaudeRequestBody(String model, String prompt) throws Exception {
        return objectMapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", claudeMaxTokens)
                .put("system",
                        "You are a senior Business Analyst. Produce a concise, well-structured BRD in Markdown " +
                        "with these sections: Overview & Purpose, Existing System Summary, Proposed Changes, " +
                        "Business Rules, APIs to Consider, Risks & Assumptions, and Next Steps. " +
                        "Be specific, actionable, and reference actual endpoints or classes where relevant.")
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", prompt)))
                .toString();
    }

    private String buildClaudeRefineRequestBody(String model, String currentMarkdown, String userPrompt) throws Exception {
        String truncated = currentMarkdown.length() > 12_000
                ? currentMarkdown.substring(0, 12_000) + "\n… (truncated)"
                : currentMarkdown;
        return objectMapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", claudeMaxTokens)
                .put("system",
                        "You are a senior Business Analyst editing an existing BRD. " +
                        "Apply the user's instruction to the BRD and return the complete revised document " +
                        "in Markdown. Keep the same structure (Overview & Purpose, Existing System Summary, " +
                        "Proposed Changes, Business Rules, APIs to Consider, Risks & Assumptions, Next Steps) " +
                        "unless the instruction explicitly requires changes to it. " +
                        "Return only the full revised Markdown — no preamble, no explanation.")
                .set("messages", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", "Existing BRD:\n\n" + truncated + "\n\n---\n\nInstruction: " + userPrompt)))
                .toString();
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildPrompt(String repoUrl, String featureContext, Object codeSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository URL: ").append(repoUrl).append('\n');
        if (featureContext != null && !featureContext.isBlank()) {
            sb.append("Feature / Business Context: ").append(featureContext).append('\n');
        }
        if (codeSummary != null) {
            String json = safeJson(codeSummary);
            sb.append("\nCode Analysis Summary (truncated if large):\n```json\n").append(json).append("\n```\n");
        }
        sb.append("\nPlease synthesize a professional BRD in Markdown based on the above. ")
                .append("Focus on business intent, current APIs/endpoints, proposed changes related to the feature context, and clear actionable next steps.");
        return sb.toString();
    }

    private String safeJson(Object obj) {
        try {
            String raw = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            if (raw.length() > MAX_SUMMARY_CHARS) {
                return raw.substring(0, MAX_SUMMARY_CHARS) + "\n... (truncated for token limit)";
            }
            return raw;
        } catch (Exception e) {
            return "{}";
        }
    }

    // ── Fallback content ──────────────────────────────────────────────────────

    private String buildStubMarkdown(String repoUrl, String featureContext, Object codeSummary) {
        String fc = (featureContext == null || featureContext.isBlank()) ? "(no feature context provided)" : featureContext;
        return "# BRD (Stub — No LLM Key Configured)\n\n" +
                "## Overview & Purpose\n" +
                "This is a placeholder BRD. Configure `llm.api.key` or `claude.api.key` in `application.properties` to enable AI-generated content.\n\n" +
                "## Input Summary\n" +
                "- **Repository:** " + repoUrl + "\n" +
                "- **Feature Context:** " + fc + "\n" +
                (codeSummary != null ? "- **Code Summary (truncated):**\n```json\n" + safeJson(codeSummary) + "\n```\n\n" : "") +
                "## Next Steps\n" +
                "1. Add `llm.api.key=<your-openai-key>` or `claude.api.key=<your-claude-key>` to `application.properties`.\n" +
                "2. Re-submit the generation request.";
    }
}
