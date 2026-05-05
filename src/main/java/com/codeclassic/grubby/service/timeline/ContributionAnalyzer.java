package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.AuthorStats;
import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.domain.model.ContributionSummary;
import com.codeclassic.grubby.domain.model.DiffSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes per-author contribution statistics and knowledge-silo detection
 * from the list of CommitRecord objects extracted for a timeline job.
 */
@Service
public class ContributionAnalyzer {

    // Top-level directory segments that are structural, not domain-indicating
    private static final Set<String> NOISE_SEGMENTS = Set.of(
            "src", "main", "java", "kotlin", "scala", "resources", "test", "tests",
            "lib", "app", "components", "pages", "api", "com", "org", "io", "net",
            "co", "uk", "internal", "pkg", "cmd", "domain", "common", "shared",
            "utils", "util", "helpers", "config", "configuration"
    );

    public ContributionSummary analyze(List<CommitRecord> commits) {
        if (commits.isEmpty()) {
            return new ContributionSummary(List.of(), 0, "", List.of());
        }

        Map<String, MutableData> byAuthor = new LinkedHashMap<>();
        Map<String, Set<String>> fileToAuthors = new HashMap<>();

        for (CommitRecord commit : commits) {
            MutableData data = byAuthor.computeIfAbsent(commit.author(), MutableData::new);
            data.commits++;
            data.totalFiles += commit.fileCount();
            data.trackDate(commit.date());

            for (DiffSummary f : commit.changedFiles()) {
                String area = meaningfulArea(f.path());
                if (!area.isBlank()) data.areas.add(area);
                fileToAuthors.computeIfAbsent(f.path(), k -> new HashSet<>()).add(commit.author());
            }
        }

        // Knowledge silos: top-level areas exclusively owned by a single author
        List<String> silos = fileToAuthors.entrySet().stream()
                .filter(e -> e.getValue().size() == 1)
                .map(e -> meaningfulArea(e.getKey()))
                .filter(a -> !a.isBlank())
                .distinct()
                .sorted()
                .limit(6)
                .collect(Collectors.toList());

        List<AuthorStats> authors = byAuthor.values().stream()
                .sorted(Comparator.comparingInt(d -> -d.commits))
                .map(d -> new AuthorStats(
                        d.name, d.commits, d.totalFiles,
                        d.firstDate != null ? d.firstDate.toString() : "",
                        d.lastDate  != null ? d.lastDate.toString()  : "",
                        d.areas.stream().limit(5).collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        String mostActive = authors.isEmpty() ? "" : authors.get(0).author();
        return new ContributionSummary(authors, authors.size(), mostActive, silos);
    }

    /**
     * Finds the first path segment that carries domain meaning (skips structural noise).
     */
    private String meaningfulArea(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.replace("\\", "/").split("/");
        for (String part : parts) {
            String clean = part.replaceAll("\\..*$", "").trim(); // strip extension
            if (!clean.isBlank() && !NOISE_SEGMENTS.contains(clean.toLowerCase())) {
                return clean;
            }
        }
        return parts.length > 0 ? parts[0] : "";
    }

    private static class MutableData {
        final String name;
        int commits;
        int totalFiles;
        LocalDate firstDate;
        LocalDate lastDate;
        final LinkedHashSet<String> areas = new LinkedHashSet<>();

        MutableData(String name) { this.name = name; }

        void trackDate(LocalDate d) {
            if (firstDate == null || d.isBefore(firstDate)) firstDate = d;
            if (lastDate  == null || d.isAfter(lastDate))  lastDate  = d;
        }
    }
}
