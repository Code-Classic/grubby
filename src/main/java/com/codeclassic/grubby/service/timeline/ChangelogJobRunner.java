package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.entity.ChangelogJob;
import com.codeclassic.grubby.domain.entity.ChangelogStatus;
import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.repository.ChangelogJobRepository;
import com.codeclassic.grubby.service.git.GitIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChangelogJobRunner {

    private final ChangelogJobRepository repo;
    private final GitIntegrationService git;
    private final ChangelogExtractorService extractor;
    private final ChangelogAiService aiService;

    @Async("brdExecutor")
    public void runAsync(Long jobId) {
        run(jobId);
    }

    public void run(Long jobId) {
        ChangelogJob job = repo.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Changelog job {} not found — skipping", jobId);
            return;
        }

        Path repoPath = null;
        try {
            update(job, ChangelogStatus.CLONING_REPO, "CLONING_REPO", 15);
            repoPath = git.cloneRepo(job.getId(), job.getRepoUrl(),
                    job.getBranch(), null, job.getAuthToken(), true);

            update(job, ChangelogStatus.EXTRACTING_COMMITS, "EXTRACTING_COMMITS", 40);
            List<CommitRecord> commits = extractor.extractBetween(
                    repoPath, job.getFromRef(), job.getToRef());

            job.setCommitCount(commits.size());
            repo.save(job);

            update(job, ChangelogStatus.GENERATING_DOCUMENT, "GENERATING_DOCUMENT", 70);
            String markdown = aiService.generate(
                    job.getRepoUrl(), job.getFromRef(), job.getToRef(), commits);

            // Prepend the version header so the AI prompt stays clean
            String effectiveTo = (job.getToRef() != null && !job.getToRef().isBlank())
                    ? job.getToRef() : "HEAD";
            String header = String.format("## [%s...%s] — %s\n\n",
                    job.getFromRef(), effectiveTo,
                    commits.isEmpty() ? "" : commits.get(commits.size() - 1).date());
            job.setMarkdownContent(header + markdown);

            job.setStatus(ChangelogStatus.COMPLETED);
            job.setStage("COMPLETED");
            job.setProgressPct(100);
            repo.save(job);
            log.info("Changelog job {} completed ({} commits)", jobId, commits.size());

        } catch (Exception ex) {
            log.error("Changelog job {} failed: {}", jobId, ex.getMessage(), ex);
            String msg = ex.getMessage();
            if (msg != null && msg.length() > 2000) msg = msg.substring(0, 2000);
            job.setStatus(ChangelogStatus.FAILED);
            job.setStage("FAILED");
            job.setProgressPct(0);
            job.setErrorMessage(msg);
            repo.save(job);
        } finally {
            if (repoPath != null) {
                try { git.deleteRecursively(repoPath); }
                catch (Exception e) { log.warn("Cleanup failed for changelog job {}: {}", jobId, e.getMessage()); }
            }
        }
    }

    private void update(ChangelogJob job, ChangelogStatus status, String stage, int pct) {
        job.setStatus(status);
        job.setStage(stage);
        job.setProgressPct(pct);
        repo.save(job);
    }
}
