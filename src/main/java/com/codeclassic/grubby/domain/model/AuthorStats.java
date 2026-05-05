package com.codeclassic.grubby.domain.model;

import java.util.List;

public record AuthorStats(
        String author,
        int commitCount,
        int totalFiles,
        String firstCommit,
        String lastCommit,
        List<String> primaryAreas
) {}
