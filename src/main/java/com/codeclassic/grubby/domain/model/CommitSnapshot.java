package com.codeclassic.grubby.domain.model;

import java.util.List;

/**
 * Lightweight, JSON-serialisable projection of CommitRecord used for visual timeline rendering.
 * Deliberately excludes patchExcerpt (can be hundreds of lines) to keep stored JSON compact.
 */
public record CommitSnapshot(
        String shortHash,
        String author,
        String date,
        String subject,
        int fileCount,
        boolean isMerge,
        boolean isTagged,
        boolean isFirst,
        int score,
        List<DiffSummary> changedFiles
) {
    public static CommitSnapshot from(CommitRecord cr) {
        return new CommitSnapshot(
                cr.shortHash(), cr.author(), cr.date().toString(), cr.subject(),
                cr.fileCount(), cr.isMerge(), cr.isTagged(), cr.isFirst(), cr.score(),
                cr.changedFiles()
        );
    }
}
