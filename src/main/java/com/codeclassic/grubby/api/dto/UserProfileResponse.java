package com.codeclassic.grubby.api.dto;

public record UserProfileResponse(
        String email,
        String firstName,
        String lastName,
        String phone,
        String githubLogin,    // null if not connected
        boolean githubConnected,
        String slackWebhookUrl // null if not configured
) {}
