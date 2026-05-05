package com.codeclassic.grubby.domain.model;

import java.time.LocalDate;

/**
 * Lightweight representation of a single git commit after smart filtering.
 */
public record CommitRecord(
        String shortHash,
        String author,
        LocalDate date,
        String subject,
        int fileCount,
        boolean isMerge,
        boolean isTagged,
        boolean isFirst,
        int score
) {
    /** One-line representation used in the AI prompt. */
    public String toPromptLine() {
        String tags = "";
        if (isFirst)  tags += "[INITIAL] ";
        if (isTagged) tags += "[RELEASE] ";
        if (isMerge)  tags += "[MERGE] ";
        return String.format("[%s] %s (%d files) | %s%s",
                date, author, fileCount, tags, subject);
    }
}
