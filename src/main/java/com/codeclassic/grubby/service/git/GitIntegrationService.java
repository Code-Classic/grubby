package com.codeclassic.grubby.service.git;

import com.codeclassic.grubby.util.RetryUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;

/**
 * Handles shallow cloning of remote Git repositories.
 *
 * Improvements:
 * - Retry config (attempts, delay, backoff) now reads from @Value instead of System.getProperty
 * - Exception unwrapping is cleaner via instanceof pattern matching
 */
@Service
public class GitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GitIntegrationService.class);

    @Value("${repo.workdir.root:./.work/repos}")
    private String workdirRoot;

    @Value("${retry.clone.attempts:3}")
    private int retryAttempts;

    @Value("${retry.clone.initialDelayMillis:1000}")
    private long retryInitialDelayMs;

    @Value("${retry.clone.backoff:2.0}")
    private double retryBackoff;

    public Path cloneRepo(long requestId, String repoUrl, String branch,
                          String commitSha, String authToken) throws GitAPIException, IOException {
        return cloneRepo(requestId, repoUrl, branch, commitSha, authToken, false);
    }

    public Path cloneRepo(long requestId, String repoUrl, String branch,
                          String commitSha, String authToken, boolean fullHistory) throws GitAPIException, IOException {
        Path root = Paths.get(workdirRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve("repo-" + requestId);

        try {
            return RetryUtils.withRetry(() -> {
                        if (Files.exists(target)) deleteRecursively(target);
                        Files.createDirectories(target);

                        var cloneCmd = Git.cloneRepository()
                                .setURI(repoUrl)
                                .setDirectory(target.toFile())
                                .setCloneAllBranches(false);

                        if (!fullHistory) {
                            cloneCmd.setDepth(1);
                        }

                        if (branch != null && !branch.isBlank()) {
                            cloneCmd.setBranch(branch);
                        }
                        if (authToken != null && !authToken.isBlank()) {
                            cloneCmd.setCredentialsProvider(
                                    new UsernamePasswordCredentialsProvider(authToken, ""));
                        }
                        try (Git git = cloneCmd.call()) {
                            if (commitSha != null && !commitSha.isBlank()) {
                                git.checkout().setName(commitSha).call();
                            }
                        }
                        log.info("Cloned repo {} into {}", safeUrl(repoUrl), target);
                        return target;
                    },
                    retryAttempts,
                    Duration.ofMillis(retryInitialDelayMs),
                    retryBackoff,
                    ex -> true);
        } catch (Exception e) {
            if (e instanceof IOException ioe) throw ioe;
            if (e instanceof GitAPIException ge) throw ge;
            if (e.getCause() instanceof IOException ioe2) throw ioe2;
            if (e.getCause() instanceof GitAPIException ge2) throw ge2;
            throw new IOException("Failed to clone repository after retries: " + e.getMessage(), e);
        }
    }

    public void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    /** Redacts tokens embedded in URLs before logging. */
    private String safeUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("[?&]access_token=[^&]+", "?access_token=***");
    }
}
