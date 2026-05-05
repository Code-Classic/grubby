package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response for integration push endpoints.
 */
@Data
@AllArgsConstructor
public class PushResponse {
    private boolean success;
    private String  issueId;    // e.g. PROJ-42 (JIRA) or UUID (Linear)
    private String  issueUrl;   // direct link to the created issue/story
    private String  message;    // human-readable outcome
}
