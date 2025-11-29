package com.codeclassic.grubby.service.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RepoFileWalker {

    private static final Logger log = LoggerFactory.getLogger(RepoFileWalker.class);

    @Value("${analysis.maxFiles:400}")
    private int maxFiles;

    @Value("${analysis.maxBytesPerFile:131072}") // 128 KB cap per file read
    private int maxBytesPerFile;

    /**
     * List Java files in a repo root, prioritizing src/main/java and src/test/java.
     */
    public List<Path> listJavaFiles(Path repoRoot) throws IOException {
        List<Path> result = new ArrayList<>();
        Path main = repoRoot.resolve("src/main/java");
        Path test = repoRoot.resolve("src/test/java");

        if (Files.isDirectory(main)) {
            result.addAll(walkJava(main));
        }
        if (Files.isDirectory(test) && result.size() < maxFiles) {
            result.addAll(walkJavaLimited(test, maxFiles - result.size()));
        }
        // Fallback: search entire repo if still empty
        if (result.isEmpty()) {
            result.addAll(walkJavaLimited(repoRoot, maxFiles));
        }
        if (result.size() > maxFiles) {
            result = result.subList(0, maxFiles);
        }
        log.info("Collected {} Java files for analysis", result.size());
        return result;
    }

    private List<Path> walkJava(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .limit(maxFiles)
                    .collect(Collectors.toList());
        }
    }

    private List<Path> walkJavaLimited(Path root, int limit) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }

    public String readHead(Path file) throws IOException {
        // Read up to maxBytesPerFile from the top of the file as a snippet
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (OutOfMemoryError e) {
            return "";
        }
        if (bytes.length > maxBytesPerFile) {
            byte[] head = new byte[maxBytesPerFile];
            System.arraycopy(bytes, 0, head, 0, maxBytesPerFile);
            return new String(head, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
