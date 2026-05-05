package com.codeclassic.grubby.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Lightweight representation of a single git commit after smart filtering.
 * changedFiles contains every file touched (type + path).
 * patchExcerpt holds a truncated unified diff for high-value commits only; null otherwise.
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
        int score,
        List<DiffSummary> changedFiles,
        String patchExcerpt
) {
    private static final int MAX_FILES_SHOWN = 14;

    /** Full representation used in the AI prompt. */
    public String toPromptLine() {
        StringBuilder sb = new StringBuilder();

        // ── Header line ──────────────────────────────────────────────────────
        String tags = "";
        if (isFirst)  tags += "[INITIAL] ";
        if (isTagged) tags += "[RELEASE] ";
        if (isMerge)  tags += "[MERGE] ";
        sb.append(String.format("[%s] %s (%d files) | %s%s", date, author, fileCount, tags, subject));

        // ── File list ─────────────────────────────────────────────────────────
        if (!changedFiles.isEmpty()) {
            sb.append("\n  Changed files:");
            int shown = 0;
            for (DiffSummary f : changedFiles) {
                if (shown >= MAX_FILES_SHOWN) {
                    sb.append(String.format("\n    ... and %d more", changedFiles.size() - MAX_FILES_SHOWN));
                    break;
                }
                sb.append("\n    ").append(symbol(f.changeType())).append(" ").append(f.path());
                shown++;
            }
        }

        // ── Patch excerpt (high-value commits only) ───────────────────────────
        if (patchExcerpt != null && !patchExcerpt.isBlank()) {
            sb.append("\n  Code changes:\n```diff\n").append(patchExcerpt).append("\n```");
        }

        return sb.toString();
    }

    private static String symbol(String changeType) {
        return switch (changeType) {
            case "ADD"    -> "[+]";
            case "DELETE" -> "[-]";
            case "RENAME" -> "[→]";
            case "COPY"   -> "[c]";
            default       -> "[~]";
        };
    }
}
