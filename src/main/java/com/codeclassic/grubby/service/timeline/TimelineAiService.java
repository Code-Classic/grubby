package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.service.ai.AiProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineAiService {

    private final AiProcessingService aiProcessingService;

    /**
     * Builds a structured prompt from filtered commit records and calls the configured LLM.
     * Returns the generated Markdown timeline document.
     */
    public String generate(String repoUrl, String branch, List<CommitRecord> commits, int totalCommits) {
        if (commits.isEmpty()) {
            return buildEmptyRepoDocument(repoUrl);
        }

        String prompt = buildSystemPrompt();
        String userMessage = buildUserMessage(repoUrl, branch, commits, totalCommits);

        log.info("Generating timeline for {} with {} commits (total: {})", repoUrl, commits.size(), totalCommits);
        return aiProcessingService.callLlmRaw(prompt, userMessage, null);
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are a technical writer and software historian. Your task is to analyse a \
                Git repository's commit history and produce a clear, insightful Development \
                Timeline document.

                Rules:
                - Use only information present in the commit history — do not invent features or dates.
                - Group commits into logical development phases; infer phase names from the work done.
                - Be specific: reference actual commit dates and authors when describing milestones.
                - Write for a technical audience who wants to understand how the project evolved.
                - Output only the Markdown document — no preamble, no apologies, no commentary.
                """;
    }

    private String buildUserMessage(String repoUrl, String branch, List<CommitRecord> commits, int total) {
        LocalDate first = commits.get(0).date();
        LocalDate last  = commits.get(commits.size() - 1).date();
        long months = ChronoUnit.MONTHS.between(first, last) + 1;

        String repoName = extractRepoName(repoUrl);
        String branchInfo = (branch != null && !branch.isBlank()) ? branch : "default";

        String commitBlock = commits.stream()
                .map(CommitRecord::toPromptLine)
                .collect(Collectors.joining("\n"));

        return """
                Repository: %s
                Branch: %s
                Project duration: %s to %s (%d months)
                Total commits in repo: %d  |  Commits analysed (representative sample): %d

                Commit history (chronological, oldest first):
                ────────────────────────────────────────────────────────────────────
                %s
                ────────────────────────────────────────────────────────────────────

                Generate a comprehensive Development Timeline document in Markdown using \
                the following structure:

                # Development Timeline: %s

                ## Project Overview
                (When the project started, what it appears to be building, who contributed, \
                approximate total duration)

                ## Development Phases
                (Group commits into 2–6 named phases. Each phase should have a date range header, \
                1–2 sentences describing what was accomplished, and bullet points for key milestones \
                with their dates)

                ## Key Milestones
                (A markdown table: | Date | Milestone | Significance |)

                ## Contributors & Collaboration Patterns
                (List contributors seen in commits, note any collaboration patterns like \
                pair-work periods, external contributions, or ownership shifts)

                ## Development Velocity & Patterns
                (Qualitative assessment: commit frequency trends, busy periods, quiet periods, \
                any notable pivots or architecture changes visible in the history)

                ## Current State & Trajectory
                (Based on the most recent commits, what is actively being worked on and \
                where is the project heading)
                """.formatted(
                repoUrl, branchInfo, first, last, months,
                total, commits.size(),
                commitBlock,
                repoName
        );
    }

    private String buildEmptyRepoDocument(String repoUrl) {
        return """
                # Development Timeline: %s

                > No commits found in this repository.

                The repository appears to be empty or the specified branch has no history.
                """.formatted(extractRepoName(repoUrl));
    }

    private static String extractRepoName(String repoUrl) {
        if (repoUrl == null) return "Unknown Project";
        String path = repoUrl.replaceAll("/$", "").replaceAll("\\.git$", "");
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
