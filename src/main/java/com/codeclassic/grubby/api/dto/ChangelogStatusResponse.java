package com.codeclassic.grubby.api.dto;

public record ChangelogStatusResponse(
        String id,
        String status,
        int progressPct,
        String stage,
        Integer commitCount,
        String errorMessage
) {}
