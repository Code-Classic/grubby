package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.api.dto.ChangelogStatusResponse;
import com.codeclassic.grubby.api.dto.GenerateChangelogRequest;
import com.codeclassic.grubby.domain.entity.ChangelogJob;
import com.codeclassic.grubby.domain.entity.ChangelogStatus;
import com.codeclassic.grubby.repository.ChangelogJobRepository;
import com.codeclassic.grubby.service.timeline.ChangelogJobRunner;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangelogOrchestratorService {

    private final ChangelogJobRepository repo;
    private final ChangelogJobRunner jobRunner;

    @Transactional
    public Long submit(GenerateChangelogRequest req, String userId) {
        ChangelogJob job = ChangelogJob.builder()
                .userId(userId)
                .repoUrl(req.getRepoUrl())
                .branch(req.getBranch())
                .authToken(req.getAuthToken())
                .fromRef(req.getFromRef())
                .toRef(req.getToRef())
                .status(ChangelogStatus.QUEUED)
                .stage("QUEUED")
                .progressPct(0)
                .build();
        repo.save(job);

        final Long id = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { jobRunner.runAsync(id); }
        });

        log.info("Queued changelog job {} for user '{}'", id, userId);
        return id;
    }

    @Transactional(readOnly = true)
    public ChangelogStatusResponse status(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public String preview(Long id) {
        ChangelogJob job = findOrThrow(id);
        if (job.getStatus() != ChangelogStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Changelog not yet available — job " + id + " has status " + job.getStatus());
        }
        return job.getMarkdownContent();
    }

    @Transactional(readOnly = true)
    public Page<ChangelogJob> list(String userId, int page, int size) {
        size = Math.min(size, 50);
        return repo.findByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    private ChangelogJob findOrThrow(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Changelog job not found: " + id));
    }

    private ChangelogStatusResponse toResponse(ChangelogJob job) {
        return new ChangelogStatusResponse(
                String.valueOf(job.getId()),
                job.getStatus().name(),
                job.getProgressPct(),
                job.getStage(),
                job.getCommitCount(),
                job.getErrorMessage()
        );
    }
}
