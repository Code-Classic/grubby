package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.domain.model.DiffSummary;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts commits between two git refs (tags, branches, or commit SHAs) for changelog
 * generation. Unlike the main timeline extractor, this does NOT apply smart scoring or
 * sampling — every commit in the range is included, since changelogs should be complete.
 * Patch excerpts are included for all commits (capped at 80 lines each) because changelog
 * ranges are typically small (one sprint or one release).
 */
@Slf4j
@Service
public class ChangelogExtractorService {

    private static final int PATCH_LINES_PER_COMMIT = 80;

    public List<CommitRecord> extractBetween(Path repoDir, String fromRef, String toRef)
            throws IOException {

        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();

            String effectiveToRef = (toRef != null && !toRef.isBlank()) ? toRef : "HEAD";
            ObjectId toId = repo.resolve(effectiveToRef);
            if (toId == null) throw new IOException("Cannot resolve ref: " + effectiveToRef);

            try (RevWalk rw = new RevWalk(repo);
                 DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

                df.setRepository(repo);
                df.setDetectRenames(false);
                rw.setRetainBody(true);

                rw.markStart(rw.parseCommit(toId));

                if (fromRef != null && !fromRef.isBlank()) {
                    ObjectId fromId = repo.resolve(fromRef);
                    if (fromId != null) {
                        rw.markUninteresting(rw.parseCommit(fromId));
                    }
                }

                List<RawEntry> raw = new ArrayList<>();
                for (RevCommit rc : rw) {
                    for (RevCommit parent : rc.getParents()) {
                        rw.parseHeaders(parent);
                    }
                    List<DiffEntry> diffs = scan(df, rc);
                    raw.add(new RawEntry(rc, diffs));
                }

                Collections.reverse(raw); // oldest first

                List<CommitRecord> result = new ArrayList<>();
                for (int i = 0; i < raw.size(); i++) {
                    RawEntry entry = raw.get(i);
                    List<DiffSummary> changedFiles = toSummaries(entry.diffs());
                    String patch = extractPatch(entry.rc(), repo, PATCH_LINES_PER_COMMIT);
                    result.add(new CommitRecord(
                            entry.rc().name().substring(0, 7),
                            entry.rc().getAuthorIdent().getName(),
                            entry.rc().getAuthorIdent().getWhen().toInstant()
                                    .atZone(ZoneOffset.UTC).toLocalDate(),
                            firstLine(entry.rc().getFullMessage()),
                            entry.diffs().size(),
                            entry.rc().getParentCount() > 1,
                            false,
                            i == 0,
                            0,
                            changedFiles,
                            patch
                    ));
                }

                log.info("Changelog extraction: {} commits between '{}' and '{}'",
                        result.size(), fromRef, effectiveToRef);
                return result;
            }
        }
    }

    // ── Private helpers (intentionally self-contained to avoid coupling) ──────

    private List<DiffEntry> scan(DiffFormatter df, RevCommit rc) {
        try {
            return rc.getParentCount() == 0
                    ? df.scan(null, rc.getTree())
                    : df.scan(rc.getParent(0).getTree(), rc.getTree());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<DiffSummary> toSummaries(List<DiffEntry> diffs) {
        return diffs.stream().map(e -> {
            String ct = e.getChangeType().name();
            String path = switch (ct) {
                case "DELETE"         -> e.getOldPath();
                case "RENAME", "COPY" -> e.getOldPath() + " → " + e.getNewPath();
                default               -> e.getNewPath();
            };
            return new DiffSummary(ct, path);
        }).collect(Collectors.toList());
    }

    private String extractPatch(RevCommit rc, Repository repo, int maxLines) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter pf = new DiffFormatter(out)) {
            pf.setRepository(repo);
            pf.setDetectRenames(false);
            pf.setContext(2);
            List<DiffEntry> diffs = rc.getParentCount() == 0
                    ? pf.scan(null, rc.getTree())
                    : pf.scan(rc.getParent(0).getTree(), rc.getTree());
            for (DiffEntry e : diffs) {
                try { pf.format(e); } catch (Exception ignored) {}
            }
            pf.flush();
            String raw = out.toString(StandardCharsets.UTF_8);
            if (raw.isBlank()) return null;
            String[] lines = raw.split("\n", -1);
            if (lines.length <= maxLines) return raw.trim();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append("\n");
            sb.append("... (").append(lines.length - maxLines).append(" more lines)");
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String msg) {
        if (msg == null || msg.isBlank()) return "(no message)";
        int nl = msg.indexOf('\n');
        String line = nl > 0 ? msg.substring(0, nl) : msg;
        return line.trim().length() > 120 ? line.trim().substring(0, 120) : line.trim();
    }

    private record RawEntry(RevCommit rc, List<DiffEntry> diffs) {}
}
