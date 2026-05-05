package com.codeclassic.grubby.domain.model;

/**
 * Lightweight record of a single file change within a commit.
 * changeType mirrors JGit DiffEntry.ChangeType: ADD, MODIFY, DELETE, RENAME, COPY.
 * path is the display path — for renames it is "oldPath → newPath".
 */
public record DiffSummary(String changeType, String path) {}
