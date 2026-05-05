package com.codeclassic.grubby.api.dto;

public record TimelineStatusResponse(
        String id,
        String status,
        int progressPct,
        String stage,
        Integer totalCommits,
        Integer analyzedCommits,
        String errorMessage
) {}
