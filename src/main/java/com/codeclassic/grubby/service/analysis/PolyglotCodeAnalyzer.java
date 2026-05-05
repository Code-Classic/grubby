package com.codeclassic.grubby.service.analysis;

import com.codeclassic.grubby.domain.model.CodeAnalysisSummary;
import com.codeclassic.grubby.domain.model.EndpointDescriptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates heuristic analysis across multiple languages (Python, TypeScript/JS, Go)
 * and merges results into a single CodeAnalysisSummary alongside Java results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolyglotCodeAnalyzer {

    private final RepoFileWalker walker;

    // ── Python patterns ───────────────────────────────────────────────────────
    private static final Pattern PY_FLASK_ROUTE = Pattern.compile(
            "@(?:app|bp|router)\\.route\\([\"']([^\"']+)[\"'].*?methods=\\[([^]]+)]");
    private static final Pattern PY_FASTAPI_ROUTE = Pattern.compile(
            "@(?:app|router)\\.(?:get|post|put|delete|patch)\\([\"']([^\"']+)[\"']");

    // ── TypeScript / JavaScript patterns ────────────────────────────────────
    private static final Pattern TS_EXPRESS_ROUTE = Pattern.compile(
            "(?:app|router)\\.(?:get|post|put|delete|patch)\\([\"'`]([^\"'`]+)[\"'`]");
    private static final Pattern TS_NEXT_HANDLER = Pattern.compile(
            "export\\s+(?:async\\s+)?function\\s+(GET|POST|PUT|DELETE|PATCH)");

    // ── Go patterns ───────────────────────────────────────────────────────────
    private static final Pattern GO_HTTP_HANDLE = Pattern.compile(
            "(?:http|mux|r|router)\\.HandleFunc?\\([\"']([^\"']+)[\"']");

    // ── Generic function / class patterns ────────────────────────────────────
    private static final Pattern PY_DEF = Pattern.compile("^def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PY_CLASS = Pattern.compile("^class\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern TS_FUNC = Pattern.compile("(?:function|const)\\s+(\\w+)\\s*(?:=|\\()");
    private static final Pattern GO_FUNC = Pattern.compile("^func\\s+(?:\\(\\w+\\s+\\*?\\w+\\)\\s+)?(\\w+)\\s*\\(", Pattern.MULTILINE);

    public CodeAnalysisSummary analyze(Path repoRoot, String featureContext) {
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        List<CodeAnalysisSummary.RelevantFile> relevantFiles = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        analyzeLanguage(repoRoot, "py", featureContext, endpoints, relevantFiles, notes, "Python");
        analyzeLanguage(repoRoot, "ts", featureContext, endpoints, relevantFiles, notes, "TypeScript");
        analyzeLanguage(repoRoot, "tsx", featureContext, endpoints, relevantFiles, notes, "TSX");
        analyzeLanguage(repoRoot, "js", featureContext, endpoints, relevantFiles, notes, "JavaScript");
        analyzeLanguage(repoRoot, "go", featureContext, endpoints, relevantFiles, notes, "Go");

        if (relevantFiles.isEmpty()) {
            notes.add("No Python, TypeScript/JS, or Go files found in repo.");
        }

        return new CodeAnalysisSummary(endpoints, relevantFiles, notes);
    }

    /**
     * Merges a language-specific summary into a combined one (for use alongside Java results).
     */
    public CodeAnalysisSummary mergeWithJava(CodeAnalysisSummary java, CodeAnalysisSummary polyglot) {
        List<EndpointDescriptor> endpoints = new ArrayList<>(java.getEndpoints());
        endpoints.addAll(polyglot.getEndpoints());

        List<CodeAnalysisSummary.RelevantFile> files = new ArrayList<>(java.getRelevantFiles());
        files.addAll(polyglot.getRelevantFiles());

        List<String> notes = new ArrayList<>(java.getNotes());
        notes.addAll(polyglot.getNotes());

        return new CodeAnalysisSummary(endpoints, files, notes);
    }

    // ── Per-language analysis ─────────────────────────────────────────────────

    private void analyzeLanguage(
            Path repoRoot, String ext, String featureContext,
            List<EndpointDescriptor> endpoints,
            List<CodeAnalysisSummary.RelevantFile> relevantFiles,
            List<String> notes, String label) {
        List<Path> files;
        try {
            files = walker.listFiles(repoRoot, ext);
        } catch (IOException e) {
            notes.add(label + " analysis skipped: " + e.getMessage());
            return;
        }
        if (files.isEmpty()) return;

        List<String> terms = featureTerms(featureContext);

        for (Path p : files) {
            String content;
            try {
                content = walker.readHead(p);
            } catch (IOException e) {
                continue;
            }
            int score = scoreFile(p, content, terms);
            if (score < 0) continue;

            String rel = repoRoot.relativize(p).toString().replace('\\', '/');
            relevantFiles.add(new CodeAnalysisSummary.RelevantFile(rel, score, firstLines(content, 60)));
            endpoints.addAll(extractEndpoints(content, rel, ext));
        }

        // keep only top 8 per language by score
        relevantFiles.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        if (relevantFiles.size() > 8) {
            relevantFiles.subList(8, relevantFiles.size()).clear();
        }
    }

    private int scoreFile(Path p, String content, List<String> terms) {
        // skip test files for relevance scoring
        String pathStr = p.toString().replace('\\', '/').toLowerCase();
        if (pathStr.contains("/test/") || pathStr.contains(".test.") || pathStr.contains(".spec.")) {
            return -1;
        }
        int score = 0;
        String name = p.getFileName().toString().toLowerCase();
        String lower = content.toLowerCase();
        for (String t : terms) {
            if (name.contains(t)) score += 3;
            if (lower.contains(t)) score += 1;
        }
        return score;
    }

    private List<EndpointDescriptor> extractEndpoints(String content, String relPath, String ext) {
        List<EndpointDescriptor> list = new ArrayList<>();
        switch (ext) {
            case "py" -> {
                addMatches(list, PY_FLASK_ROUTE.matcher(content), relPath, "Flask");
                addSingleGroupMatches(list, PY_FASTAPI_ROUTE.matcher(content), relPath, "FastAPI");
            }
            case "ts", "tsx", "js" -> {
                addSingleGroupMatches(list, TS_EXPRESS_ROUTE.matcher(content), relPath, "Express");
                extractNextHandlers(list, content, relPath);
            }
            case "go" -> addSingleGroupMatches(list, GO_HTTP_HANDLE.matcher(content), relPath, "net/http");
        }
        return list;
    }

    private void addMatches(List<EndpointDescriptor> list, Matcher m, String file, String framework) {
        while (m.find()) {
            String methods = m.groupCount() >= 2 ? m.group(2).replaceAll("[\"'\\s]", "") : "?";
            list.add(new EndpointDescriptor(methods, m.group(1), file, "?", framework));
        }
    }

    private void addSingleGroupMatches(List<EndpointDescriptor> list, Matcher m, String file, String framework) {
        while (m.find()) {
            list.add(new EndpointDescriptor("?", m.group(1), file, "?", framework));
        }
    }

    private void extractNextHandlers(List<EndpointDescriptor> list, String content, String file) {
        Matcher m = TS_NEXT_HANDLER.matcher(content);
        while (m.find()) {
            list.add(new EndpointDescriptor(m.group(1), file, file, "?", "Next.js"));
        }
    }

    private List<String> featureTerms(String featureContext) {
        if (featureContext == null || featureContext.isBlank()) return List.of();
        return Arrays.stream(featureContext.split("[\\s,.;:/]+"))
                .map(String::toLowerCase)
                .filter(s -> s.length() >= 3)
                .limit(20)
                .collect(Collectors.toList());
    }

    private String firstLines(String content, int maxLines) {
        if (content == null) return "";
        String[] lines = content.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(maxLines, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
