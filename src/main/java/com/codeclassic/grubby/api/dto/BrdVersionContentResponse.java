package com.codeclassic.grubby.api.dto;

import java.time.Instant;

public record BrdVersionContentResponse(
        Long id,
        int versionNumber,
        String changeType,
        String changePrompt,
        String createdBy,
        Instant createdAt,
        String content
) {}
