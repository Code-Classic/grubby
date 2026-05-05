package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Lightweight projection of a BrdRequest used in the paginated list endpoint.
 * Intentionally omits large fields (featureContext body, errorMessage) to keep
 * list payloads small; those are available via the individual status endpoint.
 */
@Data
@AllArgsConstructor
public class BrdListItemResponse {
    private String id;
    private String repoUrl;
    private String branch;
    private String featureContext;   // first 120 chars only — truncated server-side
    private String status;
    private String stage;
    private Integer progressPct;
    private String errorMessage;
    private Instant createdAt;
}
