package com.codeclassic.grubby.domain.entity;

public enum BrdStatus {
    QUEUED,
    CLONING_REPO,
    ANALYZING_CODE,
    GENERATING_AI_TEXT,
    FORMATTING_DOCUMENT,
    STORING_OUTPUT,
    COMPLETED,
    FAILED
}
