package com.codeclassic.grubby.domain.model;

/**
 * A detected architectural inflection point in the project's history.
 * date  — ISO date string (yyyy-MM-dd) of the commit where the signal first appeared.
 * type  — machine-readable signal type (e.g. CI_CD, DOCKER, TESTING).
 * title — human-readable short label.
 * description — one sentence explaining the detection context.
 */
public record ArchitectureSignal(String date, String type, String title, String description) {}
