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

    public String generate(String repoUrl, String branch, List<CommitRecord> commits, int totalCommits) {
        if (commits.isEmpty()) {
            return buildEmptyRepoDocument(repoUrl);
        }

        log.info("Generating timeline for {} with {} commits (total: {})", repoUrl, commits.size(), totalCommits);
        return aiProcessingService.callLlmRaw(buildSystemPrompt(), buildUserMessage(repoUrl, branch, commits, totalCommits), null);
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are a technical writer creating a Development Timeline document for a software project.
                Your audience is anyone who wants to understand how the application evolved — including \
                non-technical stakeholders, new team members, and the development team itself.

                You are given commit data that includes:
                - The commit date, author, and message
                - A list of files changed ([+] added, [~] modified, [-] deleted, [→] renamed)
                - For significant commits: the actual unified diff showing exactly what code was added or removed

                Your job is to convert this technical data into a clear, functional narrative:

                1. DESCRIBE WHAT WAS BUILT — for every meaningful commit, explain what feature, \
                   fix, or capability was implemented in plain language. Use the file names and \
                   code changes as evidence. For example, if you see a new "ExpenseCalculatorService" \
                   with methods for summing totals, say "an expense calculation feature was introduced \
                   that allows users to sum their expenses."

                2. EXPLAIN HOW IT WORKS — when code changes are provided, briefly describe the \
                   mechanics: what inputs the feature takes, what logic it applies, what it produces. \
                   Keep this concise (1–2 sentences); you are narrating, not writing documentation.

                3. TRACE EVOLUTION — when a feature appears across multiple commits, describe how \
                   it grew. For example: "Initially the expense calculator only summed totals; \
                   a later commit added per-category breakdowns and tax rate support."

                4. NAME COMPONENTS — when file paths clearly identify a component \
                   (e.g., AuthController, PaymentService, UserRepository), refer to it by name.

                Rules:
                - Base every statement strictly on the commit data provided — never invent features.
                - Write in plain English; avoid raw technical jargon where plain language works.
                - Output only the Markdown document — no preamble, disclaimers, or meta-commentary.
                """;
    }

    private String buildUserMessage(String repoUrl, String branch, List<CommitRecord> commits, int total) {
        LocalDate first = commits.get(0).date();
        LocalDate last  = commits.get(commits.size() - 1).date();
        long months = ChronoUnit.MONTHS.between(first, last) + 1;

        String repoName   = extractRepoName(repoUrl);
        String branchInfo = (branch != null && !branch.isBlank()) ? branch : "default";

        // Double-newline between commits so each block is visually distinct for the model
        String commitBlock = commits.stream()
                .map(CommitRecord::toPromptLine)
                .collect(Collectors.joining("\n\n"));

        return """
                Repository: %s
                Branch: %s
                Project duration: %s to %s (%d months)
                Total commits in repo: %d  |  Commits analysed (representative sample): %d

                Legend for file changes: [+] added  [~] modified  [-] deleted  [→] renamed
                Commits with "Code changes" blocks contain the actual unified diff for that commit.

                Commit history (chronological, oldest first):
                ════════════════════════════════════════════════════════════════════
                %s
                ════════════════════════════════════════════════════════════════════

                Generate a Development Timeline document in Markdown using this exact structure:

                # Development Timeline: %s

                ## Project Overview
                What is this application and what problem does it solve? Who built it, when did \
                development start, and how long has it been active? Write 3–5 sentences.

                ## Development Phases
                Group the commits into 2–6 named phases. For each phase include:
                - A clear phase name and date range (e.g., ### Phase 1: Foundation — Jan–Mar 2024)
                - 2–3 sentences describing the goal and outcome of the phase
                - Bullet points for each significant commit: describe in 1–2 sentences what was \
                  implemented and how it works, based on the file names and code changes shown. \
                  When a commit extends an existing feature, note what was added compared to before.

                ## Feature Evolution
                For any feature that was touched in more than one commit (added, then extended or fixed), \
                write a short paragraph tracing its lifecycle: what it could do initially, what was \
                added or changed in later commits, and what it looks like now.
                Omit this section if no feature has a multi-commit history in the data.

                ## Key Milestones
                | Date | Milestone | What was delivered |
                |------|-----------|-------------------|
                (List the 5–10 most significant moments in the project's history)

                ## Current Application Capabilities
                Based on all the commits analysed, list what the application can do today as a \
                plain-English feature list. Write each capability as a bullet point that any user \
                or stakeholder could understand — no file names or technical terms.

                ## Contributors & Development Patterns
                Who contributed? Note any collaboration patterns, ownership changes, or periods of \
                high/low activity visible in the commit history.
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
