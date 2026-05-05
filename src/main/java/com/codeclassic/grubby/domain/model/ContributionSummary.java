package com.codeclassic.grubby.domain.model;

import java.util.List;

public record ContributionSummary(
        List<AuthorStats> authors,
        int totalAuthors,
        String mostActiveAuthor,
        /** Areas (top-level packages/dirs) touched by only a single author — bus-factor risk. */
        List<String> knowledgeSilos
) {}
