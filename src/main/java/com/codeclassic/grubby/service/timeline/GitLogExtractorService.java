package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.CommitRecord;
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

import java.io.IOException;
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
 * 5. Sort ascending by date for the AI prompt.
 */
@Slf4j
@Service
public class GitLogExtractorService {

    private static final int MAX_COMMITS     = 250;
    private static final int ALWAYS_LAST_N   = 20;
    private static final int MAX_PER_MONTH   = 3;

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

    public ExtractionResult extract(Path repoDir) throws IOException {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();

            Set<String> taggedHashes = resolveTaggedHashes(git, repo);

            // Walk commits and score/annotate in a single RevWalk+DiffFormatter scope
            // so parent trees are still accessible when computing file counts.
            try (RevWalk rw = new RevWalk(repo);
                 DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

                df.setRepository(repo);
                df.setDetectRenames(false);
                rw.setRetainBody(true); // keep commit message bodies in memory

                ObjectId head = repo.resolve("HEAD");
                if (head == null) return new ExtractionResult(List.of(), 0);
                rw.markStart(rw.parseCommit(head));

                List<RawCommit> all = new ArrayList<>();
                for (RevCommit rc : rw) {
                    // parseCommit on parents so their trees are available for diff
                    for (RevCommit parent : rc.getParents()) {
                        rw.parseHeaders(parent);
                    }
                    all.add(new RawCommit(rc.name(), rc, taggedHashes.contains(rc.name())));
                }

                int total = all.size();
                log.info("Repository has {} total commits", total);
                if (total == 0) return new ExtractionResult(List.of(), 0);

                // Oldest first
                Collections.reverse(all);

                List<CommitRecord> scored = new ArrayList<>(total);
                for (int i = 0; i < all.size(); i++) {
                    RawCommit raw = all.get(i);
                    boolean isFirst = (i == 0);
                    int fileCount = countFilesChanged(df, raw.rc);
                    int score = computeScore(raw.rc, fileCount, raw.isTagged, isFirst);
                    scored.add(new CommitRecord(
                            raw.rc.name().substring(0, 7),
                            raw.rc.getAuthorIdent().getName(),
                            toLocalDate(raw.rc.getAuthorIdent().getWhen().toInstant()),
                            firstLine(raw.rc.getFullMessage()),
                            fileCount,
                            raw.rc.getParentCount() > 1,
                            raw.isTagged,
                            isFirst,
                            score
                    ));
                }

                List<CommitRecord> selected = select(scored);
                log.info("Selected {} commits from {} for AI prompt", selected.size(), total);
                return new ExtractionResult(selected, total);
            }
        }
    }

    // ── Selection logic ───────────────────────────────────────────────────────

    private List<CommitRecord> select(List<CommitRecord> scored) {
        // Pin: first, all tagged, last N — always in
        Set<String> pinned = new LinkedHashSet<>();
        scored.stream().filter(CommitRecord::isFirst).map(CommitRecord::shortHash).forEach(pinned::add);
        scored.stream().filter(CommitRecord::isTagged).map(CommitRecord::shortHash).forEach(pinned::add);

        int lastN = Math.min(ALWAYS_LAST_N, scored.size());
        scored.subList(scored.size() - lastN, scored.size())
              .forEach(c -> pinned.add(c.shortHash()));

        // Monthly coverage: top MAX_PER_MONTH per calendar month from remaining
        Map<YearMonth, List<CommitRecord>> byMonth = scored.stream()
                .collect(Collectors.groupingBy(c -> YearMonth.from(c.date())));

        Set<String> monthly = new LinkedHashSet<>();
        byMonth.forEach((month, commits) -> commits.stream()
                .sorted(Comparator.comparingInt(CommitRecord::score).reversed())
                .limit(MAX_PER_MONTH)
                .forEach(c -> monthly.add(c.shortHash())));

        // Merge: pinned first (preserves order), then monthly
        Set<String> combined = new LinkedHashSet<>(pinned);
        combined.addAll(monthly);

        // Rebuild in chronological order, capped at MAX_COMMITS
        return scored.stream()
                .filter(c -> combined.contains(c.shortHash()))
                .limit(MAX_COMMITS)
                .collect(Collectors.toList());
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private int computeScore(RevCommit rc, int fileCount, boolean isTagged, boolean isFirst) {
        int score = 0;
        if (isFirst)  score += 15;
        if (isTagged) score += 12;
        if (rc.getParentCount() > 1) score += 5;  // merge commit

        // File-count proxy for changeset size
        if (fileCount > 20) score += 8;
        else if (fileCount > 8) score += 4;
        else if (fileCount > 3) score += 2;

        String subject = firstLine(rc.getFullMessage());
        if (KW_GENESIS.matcher(subject).find())  score += 6;
        if (KW_FEATURE.matcher(subject).find())  score += 5;
        if (KW_ARCH.matcher(subject).find())     score += 4;
        if (KW_IMPROVE.matcher(subject).find())  score += 3;

        return score;
    }

    // ── JGit helpers ─────────────────────────────────────────────────────────

    private int countFilesChanged(DiffFormatter df, RevCommit rc) {
        try {
            if (rc.getParentCount() == 0) {
                // Initial commit — diff against empty tree
                List<DiffEntry> diffs = df.scan(null, rc.getTree());
                return diffs.size();
            }
            List<DiffEntry> diffs = df.scan(rc.getParent(0).getTree(), rc.getTree());
            return diffs.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private Set<String> resolveTaggedHashes(Git git, Repository repo) {
        Set<String> hashes = new HashSet<>();
        try {
            for (Ref tag : git.tagList().call()) {
                // Tags may be annotated (→ peel to the commit) or lightweight
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

    private record RawCommit(String hash, RevCommit rc, boolean isTagged) {}
}
