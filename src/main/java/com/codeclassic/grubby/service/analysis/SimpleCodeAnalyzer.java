package com.codeclassic.grubby.service.analysis;

import com.codeclassic.grubby.domain.model.CodeAnalysisSummary;
import com.codeclassic.grubby.domain.model.EndpointDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SimpleCodeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SimpleCodeAnalyzer.class);

    // Regexes to extract Spring endpoints very roughly
    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController|@Controller");
    private static final Pattern REQ_MAPPING_CLASS = Pattern.compile("@RequestMapping\\(value?=\\\"([^\\\"]*)\\\".*?\\)");
    private static final Pattern REQ_MAPPING_METHOD = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\((?:value=)?\\\"([^\\\"]*)\\\".*?\\)");

    public CodeAnalysisSummary analyze(Path repoRoot, List<Path> javaFiles, String featureContext, RepoFileWalker walker) {
        Map<Path, Integer> scores = new HashMap<>();
        String fc = featureContext == null ? "" : featureContext.toLowerCase();
        List<String> terms = Arrays.stream(fc.split("[\\s,.;:/]+"))
                .filter(s -> s.length() >= 3)
                .limit(20)
                .collect(Collectors.toList());

        for (Path p : javaFiles) {
            int score = 0;
            String name = p.getFileName().toString().toLowerCase();
            for (String t : terms) {
                if (name.contains(t)) score += 3;
            }
            // light content scan of head
            try {
                String head = walker.readHead(p).toLowerCase();
                for (String t : terms) {
                    if (head.contains(t)) score += 1;
                }
                // controllers get a small boost
                if (head.contains("@restcontroller") || head.contains("@controller")) score += 2;
            } catch (IOException ignored) {}
            scores.put(p, score);
        }

        // select top 12 files
        List<Map.Entry<Path, Integer>> top = scores.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .collect(Collectors.toList());

        List<CodeAnalysisSummary.RelevantFile> relevant = new ArrayList<>();
        List<EndpointDescriptor> endpoints = new ArrayList<>();

        for (var e : top) {
            Path p = e.getKey();
            int score = e.getValue();
            try {
                String text = walker.readHead(p);
                relevant.add(new CodeAnalysisSummary.RelevantFile(repoRoot.relativize(p).toString(), score, firstLines(text, 80))); // 80 lines approx by chars
                endpoints.addAll(extractEndpoints(text, p.getFileName().toString()));
            } catch (IOException ignored) {}
        }

        List<String> notes = List.of("Heuristic analyzer used; values may be approximate.");
        return new CodeAnalysisSummary(endpoints, relevant, notes);
    }

    private String firstLines(String content, int maxLines) {
        if (content == null) return "";
        String[] lines = content.split("\\R");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxLines, lines.length);
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private List<EndpointDescriptor> extractEndpoints(String text, String fileName) {
        if (text == null) return List.of();
        List<EndpointDescriptor> list = new ArrayList<>();
        Matcher mClassMap = REQ_MAPPING_CLASS.matcher(text);
        String base = "";
        if (mClassMap.find()) {
            base = mClassMap.group(1);
        }
        Matcher m = REQ_MAPPING_METHOD.matcher(text);
        while (m.find()) {
            String ann = m.group(1);
            String path = m.group(2);
            String http = ann.replace("Mapping", "").toUpperCase();
            list.add(new EndpointDescriptor(http, base + path, fileName.replace(".java",""), "?", "Detected via annotations"));
        }
        return list;
    }
}
