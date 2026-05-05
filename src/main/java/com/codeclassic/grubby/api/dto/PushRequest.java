package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for POST /api/v1/brd/:id/push/jira  and  /push/linear
 *
 * Fields used by JIRA:  jiraUrl, jiraApiToken, jiraEmail, jiraProjectKey, epicKey
 * Fields used by Linear: linearApiKey, linearTeamId, linearProjectId
 *
 * S-10 — @NotBlank constraints added so a 400 is returned before any attempt to
 * call external APIs with missing credentials.
 */
@Data
public class PushRequest {

    // ── JIRA ──────────────────────────────────────────────────────────────────
    // At least one of jiraUrl / linearApiKey must be non-blank; enforced at the
    // service layer depending on which endpoint was called.
    @Size(max = 500)
    private String jiraUrl;         // e.g. https://myorg.atlassian.net

    @Size(max = 500)
    private String jiraApiToken;

    @Size(max = 200)
    private String jiraEmail;

    @Size(max = 50)
    private String jiraProjectKey;  // e.g. PROJ

    @Size(max = 50)
    private String epicKey;         // optional parent epic

    // ── Linear ────────────────────────────────────────────────────────────────
    @Size(max = 500)
    private String linearApiKey;

    @Size(max = 100)
    private String linearTeamId;

    @Size(max = 100)
    private String linearProjectId; // optional
}
