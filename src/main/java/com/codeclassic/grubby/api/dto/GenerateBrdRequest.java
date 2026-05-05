package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * P2 — repoUrl now carries basic format constraints (scheme + length).
 * Deep SSRF validation (IP range + DB allowlist) happens in RepoUrlValidator,
 * called from BrdOrchestratorService.submit() before any clone is attempted.
 */
@Data
public class GenerateBrdRequest {

    /**
     * Must start with an allowed scheme.  Full SSRF validation is done server-side
     * in RepoUrlValidator — this annotation is a first-pass sanity check only.
     */
    @NotBlank
    @Size(max = 500, message = "repoUrl must not exceed 500 characters")
    @Pattern(
        regexp = "^(https?|git|ssh)://.*",
        message = "repoUrl must start with https://, http://, git://, or ssh://"
    )
    private String repoUrl;

    @Size(max = 200)
    private String branch;

    @Size(max = 40)
    private String commitSha;

    @Size(max = 5000)
    private String featureContext;

    @Size(max = 100)
    private String projectType;

    /** none | token | oauth */
    @Size(max = 20)
    private String authType;

    /** Git auth token — never persisted beyond the active job */
    @Size(max = 500)
    private String authToken;

    private boolean forceReanalyze;

    /** Model identifier, e.g. "gpt-4o-mini" or "claude-opus-4-7". Null/blank = server default. */
    @Size(max = 100)
    private String aiModel;

    /** Optional Slack Incoming Webhook URL for completion notifications. */
    @Size(max = 500)
    private String slackWebhookUrl;

    private Map<String, String> options;
}
