package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.domain.model.DiffSummary;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts and intelligently filters a repository's commit history for AI timeline generation.
 *
 * Summarization strategy (designed for real-world repos with thousands of commits):
 *
 * 1. Score every commit:
 *    - First commit:         +15
 *    - Tagged (release):     +12
 *    - Merge commit:         +5
 *    - Large changeset:      +8 (>20 files), +4 (>8 files), +2 (>3 files)
 *    - Keyword match:        +6 for genesis keywords, +5 for feature keywords,
 *                            +4 for architecture keywords, +3 for fix/improve keywords
 *
 * 2. Always include: first commit, all tagged commits, last 20 commits.
 *
 * 3. Monthly coverage: for each calendar month with commits, take up to top 3
 *    by score (guarantees no long gaps in the timeline even for sparse months).
 *
 * 4. Hard cap at 250 commits — keeps the AI prompt well within context limits.
 *
 * 5. Patch excerpts: extracted for initial, tagged, and high-score commits (score >= 10)
 *    using a real DiffFormatter so the AI can describe what functionality was implemented.
 *    File-list-only for lower-signal commits (cheap; no content read from object store).
 *
 * 6. Sort ascending by date for the AI prompt.
 */
@Slf4j
@Service
public class GitLogExtractorService {

    private static final int MAX_COMMITS   = 250;
    private static final int ALWAYS_LAST_N = 20;
    private static final int MAX_PER_MONTH = 3;

    // Patch excerpt line budgets per commit tier
    private static final int PATCH_LINES_INITIAL = 200;
    private static final int PATCH_LINES_RELEASE  = 120;
    private static final int PATCH_LINES_HIGH     = 100; // score >= 15
    private static final int PATCH_LINES_NOTABLE  = 60;  // score >= 10

    // Keyword tiers for scoring commit messages (case-insensitive)
    private static final Pattern KW_GENESIS = Pattern.compile(
            "(?i)\\b(initial|init|scaffold|bootstrap|setup|first|start|begin|create project|genesis)\\b");
    private static final Pattern KW_FEATURE = Pattern.compile(
            "(?i)\\b(feat|feature|implement|introduce|add|new|build|create|develop)\\b");
    private static final Pattern KW_ARCH    = Pattern.compile(
            "(?i)\\b(migrate|migration|upgrade|breaking|major|refactor|redesign|rewrite|architecture)\\b");
    private static final Pattern KW_IMPROVE = Pattern.compile(
            "(?i)\\b(fix|patch|improve|enhance|optimis|optimiz|performance|security|release|deploy|version|v\\d+\\.\\d+)\\b");

    public record ExtractionResult(List<CommitRecord> commits, int totalCommits) {}

    // Internal intermediates — not exposed outside this class
    private record RawCommit(RevCommit rc, boolean isTagged, List<DiffEntry> diffs) {}
    private record ScoredCommit(RevCommit rc, boolean isTagged, boolean isFirst,
                                int fileCount, int score, List<DiffEntry> diffs) {}

    // ── Public entry point ────────────────────────────────────────────────────

    public ExtractionResult extract(Path repoDir) throws IOException {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();

            Set<String> taggedHashes = resolveTaggedHashes(git, repo);

            try (RevWalk rw = new RevWalk(repo);
                 DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

                df.setRepository(repo);
                df.setDetectRenames(false);
                rw.setRetainBody(true);

                ObjectId head = repo.resolve("HEAD");
                if (head == null) return new ExtractionResult(List.of(), 0);
                rw.markStart(rw.parseCommit(head));

                // Phase 1 — walk all commits; collect DiffEntry lists (no file content read yet)
                List<RawCommit> all = new ArrayList<>();
                for (RevCommit rc : rw) {
                    for (RevCommit parent : rc.getParents()) {
                        rw.parseHeaders(parent);
                    }
                    List<DiffEntry> diffs = scanDiffs(df, rc);
                    all.add(new RawCommit(rc, taggedHashes.contains(rc.name()), diffs));
                }

                int total = all.size();
                log.info("Repository has {} total commits", total);
                if (total == 0) return new ExtractionResult(List.of(), 0);

                Collections.reverse(all); // oldest first

                // Phase 2 — score all commits
                List<ScoredCommit> scored = new ArrayList<>(total);
                for (int i = 0; i < all.size(); i++) {
                    RawCommit raw = all.get(i);
                    boolean isFirst = (i == 0);
                    int score = computeScore(raw.rc(), raw.diffs().size(), raw.isTagged(), isFirst);
                    scored.add(new ScoredCommit(
                            raw.rc(), raw.isTagged(), isFirst,
                            raw.diffs().size(), score, raw.diffs()));
                }

                // Phase 3 — select representative subset
                List<ScoredCommit> selected = select(scored);
                log.info("Selected {} commits from {} for AI prompt", selected.size(), total);

                // Phase 4 — build CommitRecords with file list + patch excerpts
                // Patch extraction reads object content from the repo (still open) for
                // high-value commits only, keeping total prompt size manageable.
                List<CommitRecord> result = new ArrayList<>(selected.size());
                for (ScoredCommit sc : selected) {
                    List<DiffSummary> changedFiles = buildDiffSummaries(sc.diffs());
                    String patchExcerpt = extractPatchExcerpt(sc, repo);
                    result.add(new CommitRecord(
                            sc.rc().name().substring(0, 7),
                            sc.rc().getAuthorIdent().getName(),
                            toLocalDate(sc.rc().getAuthorIdent().getWhen().toInstant()),
                            firstLine(sc.rc().getFullMessage()),
                            sc.fileCount(),
                            sc.rc().getParentCount() > 1,
                            sc.isTagged(),
                            sc.isFirst(),
                            sc.score(),
                            changedFiles,
                            patchExcerpt
                    ));
                }

                return new ExtractionResult(result, total);
            }
        }
    }

    // ── Selection logic ───────────────────────────────────────────────────────

    private List<ScoredCommit> select(List<ScoredCommit> all) {
        // Pin: first commit, all tagged commits, last N commits — always included
        Set<String> pinned = new LinkedHashSet<>();
        all.stream().filter(ScoredCommit::isFirst)
                .map(sc -> sc.rc().name()).forEach(pinned::add);
        all.stream().filter(ScoredCommit::isTagged)
                .map(sc -> sc.rc().name()).forEach(pinned::add);
        int lastN = Math.min(ALWAYS_LAST_N, all.size());
        all.subList(all.size() - lastN, all.size())
                .forEach(sc -> pinned.add(sc.rc().name()));

        // Monthly coverage: top MAX_PER_MONTH by score for each calendar month
        Map<YearMonth, List<ScoredCommit>> byMonth = all.stream()
                .collect(Collectors.groupingBy(sc ->
                        YearMonth.from(toLocalDate(sc.rc().getAuthorIdent().getWhen().toInstant()))));

        Set<String> monthly = new LinkedHashSet<>();
        byMonth.forEach((month, commits) -> commits.stream()
                .sorted(Comparator.comparingInt(ScoredCommit::score).reversed())
                .limit(MAX_PER_MONTH)
                .forEach(sc -> monthly.add(sc.rc().name())));

        Set<String> combined = new LinkedHashSet<>(pinned);
        combined.addAll(monthly);

        return all.stream()
                .filter(sc -> combined.contains(sc.rc().name()))
                .limit(MAX_COMMITS)
                .collect(Collectors.toList());
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private int computeScore(RevCommit rc, int fileCount, boolean isTagged, boolean isFirst) {
        int score = 0;
        if (isFirst)  score += 15;
        if (isTagged) score += 12;
        if (rc.getParentCount() > 1) score += 5; // merge commit

        if (fileCount > 20)      score += 8;
        else if (fileCount > 8)  score += 4;
        else if (fileCount > 3)  score += 2;

        String subject = firstLine(rc.getFullMessage());
        if (KW_GENESIS.matcher(subject).find())  score += 6;
        if (KW_FEATURE.matcher(subject).find())  score += 5;
        if (KW_ARCH.matcher(subject).find())     score += 4;
        if (KW_IMPROVE.matcher(subject).find())  score += 3;

        return score;
    }

    // ── Diff helpers ──────────────────────────────────────────────────────────

    /** Fast scan using DisabledOutputStream formatter — gives entry list without reading file content. */
    private List<DiffEntry> scanDiffs(DiffFormatter df, RevCommit rc) {
        try {
            if (rc.getParentCount() == 0) {
                return df.scan(null, rc.getTree());
            }
            return df.scan(rc.getParent(0).getTree(), rc.getTree());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<DiffSummary> buildDiffSummaries(List<DiffEntry> diffs) {
        return diffs.stream().map(entry -> {
            String ct = entry.getChangeType().name();
            String path = switch (ct) {
                case "DELETE"         -> entry.getOldPath();
                case "RENAME", "COPY" -> entry.getOldPath() + " → " + entry.getNewPath();
                default               -> entry.getNewPath();
            };
            return new DiffSummary(ct, path);
        }).collect(Collectors.toList());
    }

    /**
     * Extracts a truncated unified diff for high-value commits.
     * Uses a fresh DiffFormatter backed by a ByteArrayOutputStream so actual
     * file content is read from the object store and formatted as a patch.
     * Returns null for commits below the notable threshold (score < 10).
     */
    private String extractPatchExcerpt(ScoredCommit sc, Repository repo) {
        int maxLines = patchLineLimit(sc);
        if (maxLines == 0) return null;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter pf = new DiffFormatter(out)) {

            pf.setRepository(repo);
            pf.setDetectRenames(false);
            pf.setContext(2);

            List<DiffEntry> diffs;
            if (sc.rc().getParentCount() == 0) {
                diffs = pf.scan(null, sc.rc().getTree());
            } else {
                diffs = pf.scan(sc.rc().getParent(0).getTree(), sc.rc().getTree());
            }

            for (DiffEntry entry : diffs) {
                try {
                    pf.format(entry);
                } catch (Exception ignored) {
                    // binary or corrupt object — skip without failing the whole excerpt
                }
            }
            pf.flush();

            String raw = out.toString(StandardCharsets.UTF_8);
            if (raw.isBlank()) return null;

            String[] lines = raw.split("\n", -1);
            if (lines.length <= maxLines) return raw.trim();

            StringBuilder truncated = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                truncated.append(lines[i]).append("\n");
            }
            truncated.append("... (").append(lines.length - maxLines).append(" more lines)");
            return truncated.toString().trim();

        } catch (Exception e) {
            log.debug("Patch extraction failed for {}: {}", sc.rc().name(), e.getMessage());
            return null;
        }
    }

    private static int patchLineLimit(ScoredCommit sc) {
        if (sc.isFirst())     return PATCH_LINES_INITIAL;
        if (sc.isTagged())    return PATCH_LINES_RELEASE;
        if (sc.score() >= 15) return PATCH_LINES_HIGH;
        if (sc.score() >= 10) return PATCH_LINES_NOTABLE;
        return 0;
    }

    // ── JGit helpers ─────────────────────────────────────────────────────────

    private Set<String> resolveTaggedHashes(Git git, Repository repo) {
        Set<String> hashes = new HashSet<>();
        try {
            for (Ref tag : git.tagList().call()) {
                ObjectId peeled = repo.getRefDatabase().peel(tag).getPeeledObjectId();
                ObjectId target = (peeled != null) ? peeled : tag.getObjectId();
                if (target != null) hashes.add(target.name());
            }
        } catch (Exception e) {
            log.debug("Could not resolve tags: {}", e.getMessage());
        }
        return hashes;
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "(no message)";
        int nl = message.indexOf('\n');
        String line = nl > 0 ? message.substring(0, nl) : message;
        return line.trim().length() > 120 ? line.trim().substring(0, 120) : line.trim();
    }

    private static LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
