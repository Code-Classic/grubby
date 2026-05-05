package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.entity.TimelineJob;
import com.codeclassic.grubby.domain.entity.TimelineStatus;
import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.repository.TimelineJobRepository;
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
public class TimelineJobRunner {

    private final TimelineJobRepository repo;
    private final GitIntegrationService git;
    private final GitLogExtractorService extractor;
    private final TimelineAiService aiService;

    @Async("brdExecutor")
    public void runAsync(Long jobId) {
        run(jobId);
    }

    public void run(Long jobId) {
        TimelineJob job = repo.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Timeline job {} not found — skipping", jobId);
            return;
        }

        Path repoPath = null;
        try {
            // Stage 1: Clone repository
            update(job, TimelineStatus.CLONING_REPO, "CLONING_REPO", 10);
            repoPath = git.cloneRepo(job.getId(), job.getRepoUrl(),
                    job.getBranch(), null, job.getAuthToken());

            // Stage 2: Extract and filter commits
            update(job, TimelineStatus.EXTRACTING_COMMITS, "EXTRACTING_COMMITS", 35);
            GitLogExtractorService.ExtractionResult result = extractor.extract(repoPath);
            List<CommitRecord> commits = result.commits();

            job.setTotalCommits(result.totalCommits());
            job.setAnalyzedCommits(commits.size());
            repo.save(job);
            log.info("Job {}: extracted {}/{} commits", jobId, commits.size(), result.totalCommits());

            // Stage 3: Generate timeline document with AI
            update(job, TimelineStatus.GENERATING_DOCUMENT, "GENERATING_DOCUMENT", 65);
            String markdown = aiService.generate(job.getRepoUrl(), job.getBranch(), commits, result.totalCommits());

            // Complete
            job.setMarkdownContent(markdown);
            job.setStatus(TimelineStatus.COMPLETED);
            job.setStage("COMPLETED");
            job.setProgressPct(100);
            repo.save(job);
            log.info("Timeline job {} completed ({} → {} commits)", jobId, result.totalCommits(), commits.size());

        } catch (Exception ex) {
            log.error("Timeline job {} failed: {}", jobId, ex.getMessage(), ex);
            String msg = ex.getMessage();
            if (msg != null && msg.length() > 2000) msg = msg.substring(0, 2000);
            job.setStatus(TimelineStatus.FAILED);
            job.setStage("FAILED");
            job.setProgressPct(0);
            job.setErrorMessage(msg);
            repo.save(job);
        } finally {
            if (repoPath != null) {
                try {
                    git.deleteRecursively(repoPath);
                } catch (Exception e) {
                    log.warn("Cleanup failed for timeline job {}: {}", jobId, e.getMessage());
                }
            }
        }
    }

    private void update(TimelineJob job, TimelineStatus status, String stage, int pct) {
        job.setStatus(status);
        job.setStage(stage);
        job.setProgressPct(pct);
        repo.save(job);
        log.debug("Timeline job {} → {} ({}%)", job.getId(), stage, pct);
    }
}
