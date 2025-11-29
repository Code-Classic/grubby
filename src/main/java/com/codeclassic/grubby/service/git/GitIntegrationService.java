package com.codeclassic.grubby.service.git;

import com.codeclassic.grubby.util.RetryUtils;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class GitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GitIntegrationService.class);

    @Value("${repo.workdir.root:./.work/repos}")
    private String workdirRoot;

    /**
     * Shallow clone of a git repo into a per-request directory.
     * @param requestId used to name the working directory
     * @param repoUrl repository URL (https/ssh)
     * @param branch optional branch
     * @param commitSha optional specific commit (if provided, branch is ignored after clone)
     * @param authToken optional token for private repos (dev only)
     * @return Path to the local cloned repository
     */
    public Path cloneRepo(long requestId, String repoUrl, String branch, String commitSha, String authToken)
            throws GitAPIException, IOException {
        Path root = Paths.get(workdirRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve("repo-" + requestId);

        // Retryable clone operation
        try {
            return RetryUtils.withRetry(() -> {
                // Clean if exists
                if (Files.exists(target)) {
                    deleteRecursively(target);
                }
                Files.createDirectories(target);

                var cloneCmd = Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(target.toFile())
                        .setCloneAllBranches(false)
                        .setDepth(1);
                if (branch != null && !branch.isBlank()) {
                    cloneCmd.setBranch(branch);
                }
                if (authToken != null && !authToken.isBlank()) {
                    // For GitHub over HTTPS, token as username with blank password also works
                    cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(authToken, ""));
                }
                try (Git git = cloneCmd.call()) {
                    if (commitSha != null && !commitSha.isBlank()) {
                        // checkout specific commit in detached HEAD
                        git.checkout().setName(commitSha).call();
                    }
                }
                log.info("Cloned repo {} into {}", safeRepoUrl(repoUrl), target);
                return target;
            },
            // attempts & backoff from properties (fallback defaults)
            Integer.getInteger("retry.clone.attempts", 3),
            java.time.Duration.ofMillis(Long.getLong("retry.clone.initialDelayMillis", 1000L)),
            Double.parseDouble(System.getProperty("retry.clone.backoff", "2.0")),
            ex -> true);
        } catch (Exception e) {
            if (e instanceof IOException ioe) throw ioe;
            if (e instanceof GitAPIException ge) throw ge;
            if (e.getCause() instanceof IOException ioe2) throw ioe2;
            if (e.getCause() instanceof GitAPIException ge2) throw ge2;
            throw new IOException("Failed to clone repository after retries: " + e.getMessage(), e);
        }
    }

    private String safeRepoUrl(String url) {
        if (url == null) return null;
        // redact tokens if present in URL patterns, very basic
        return url.replaceAll("[?&]access_token=[^&]+", "?access_token=***");
    }

    public void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
