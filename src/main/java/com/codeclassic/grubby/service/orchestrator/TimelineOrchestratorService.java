package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.api.dto.GenerateTimelineRequest;
import com.codeclassic.grubby.api.dto.TimelineStatusResponse;
import com.codeclassic.grubby.domain.entity.TimelineJob;
import com.codeclassic.grubby.domain.entity.TimelineStatus;
import com.codeclassic.grubby.repository.TimelineJobRepository;
import com.codeclassic.grubby.service.timeline.TimelineJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineOrchestratorService {

    private final TimelineJobRepository repo;
    private final TimelineJobRunner jobRunner;

    @Transactional
    public Long submit(GenerateTimelineRequest req, String userId) {
        TimelineJob job = TimelineJob.builder()
                .userId(userId)
                .repoUrl(req.getRepoUrl())
                .branch(req.getBranch())
                .authToken(req.getAuthToken())
                .status(TimelineStatus.QUEUED)
                .stage("QUEUED")
                .progressPct(0)
                .build();
        repo.save(job);

        final Long id = job.getId();
        // Fire after commit so the worker's findById() sees the row
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { jobRunner.runAsync(id); }
        });

        log.info("Queued timeline job {} for user '{}'", id, userId);
        return id;
    }

    @Transactional(readOnly = true)
    public TimelineStatusResponse status(Long id) {
        TimelineJob job = findOrThrow(id);
        return toStatusResponse(job);
    }

    @Transactional(readOnly = true)
    public String preview(Long id) {
        TimelineJob job = findOrThrow(id);
        if (job.getStatus() != TimelineStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Timeline not yet available — job " + id + " has status " + job.getStatus());
        }
        return job.getMarkdownContent();
    }

    @Transactional(readOnly = true)
    public Page<TimelineJob> list(String userId, int page, int size) {
        size = Math.min(size, 50);
        return repo.findByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TimelineJob findOrThrow(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Timeline job not found: " + id));
    }

    private TimelineStatusResponse toStatusResponse(TimelineJob job) {
        return new TimelineStatusResponse(
                String.valueOf(job.getId()),
                job.getStatus().name(),
                job.getProgressPct(),
                job.getStage(),
                job.getTotalCommits(),
                job.getAnalyzedCommits(),
                job.getErrorMessage()
        );
    }
}
