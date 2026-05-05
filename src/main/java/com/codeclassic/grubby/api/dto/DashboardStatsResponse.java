package com.codeclassic.grubby.api.dto;

public record DashboardStatsResponse(
        long total,
        long completed,
        long inProgress,
        long failed
) {}
