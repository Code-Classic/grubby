package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.service.ai.AiProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangelogAiService {

    private final AiProcessingService aiProcessingService;

    public String generate(String repoUrl, String fromRef, String toRef,
                           List<CommitRecord> commits) {
        if (commits.isEmpty()) {
            return String.format("## [%s...%s]\n\nNo commits found between these references.",
                    fromRef, toRef != null ? toRef : "HEAD");
        }

        log.info("Generating changelog for {} ({} → {}) with {} commits",
                repoUrl, fromRef, toRef, commits.size());
        return aiProcessingService.callLlmRaw(
                buildSystemPrompt(),
                buildUserMessage(repoUrl, fromRef, toRef, commits),
                null
        );
    }

    private String buildSystemPrompt() {
        return """
                You are a technical writer generating a software project changelog.

                Follow Keep a Changelog format (https://keepachangelog.com/):
                - Use sections: ### Added, ### Changed, ### Fixed, ### Deprecated, ### Removed, ### Security
                - Only include sections that have relevant entries
                - Each entry is a bullet point starting with a verb (e.g. "Add", "Fix", "Remove")
                - Write for developers and users — be specific about what changed functionally
                - Use the file names and code changes as evidence; name components explicitly
                - Do NOT include a version header — the caller will add it
                - Do NOT add preamble or commentary — output only the changelog sections
                - Classify each commit into the appropriate section based on file changes and message:
                  * New files/features → Added
                  * Modified behaviour → Changed
                  * Bug fixes → Fixed
                  * Security-related → Security
                  * Removed files/features → Removed
                """;
    }

    private String buildUserMessage(String repoUrl, String fromRef, String toRef,
                                    List<CommitRecord> commits) {
        LocalDate first = commits.get(0).date();
        LocalDate last  = commits.get(commits.size() - 1).date();
        String repo = extractRepoName(repoUrl);
        String effectiveTo = (toRef != null && !toRef.isBlank()) ? toRef : "HEAD";

        String commitBlock = commits.stream()
                .map(CommitRecord::toPromptLine)
                .collect(Collectors.joining("\n\n"));

        return """
                Repository: %s
                Range: %s → %s
                Period: %s to %s
                Commits in range: %d

                Commit history (oldest first):
                ════════════════════════════════════════════════════════════════════
                %s
                ════════════════════════════════════════════════════════════════════

                Generate a Keep a Changelog document for the %s project covering the range
                %s → %s. Group every commit into the correct section (Added / Changed / Fixed /
                Deprecated / Removed / Security). Each bullet should describe the functional
                change in plain language — reference component names from file paths and
                describe how behaviour changed based on the code diffs shown.
                """.formatted(
                repoUrl, fromRef, effectiveTo, first, last, commits.size(),
                commitBlock,
                repo, fromRef, effectiveTo
        );
    }

    private static String extractRepoName(String url) {
        if (url == null) return "project";
        String s = url.replaceAll("/$", "").replaceAll("\\.git$", "");
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }
}
