package com.codeclassic.grubby.service.git;

import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import com.codeclassic.grubby.repository.BrdRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkdirCleanupService {

    private final BrdRequestRepository brdRequestRepository;
    private final GitIntegrationService gitIntegrationService;

    @Value("${workdir.cleanup.enabled:true}")
    private boolean enabled;

    @Value("${repo.workdir.root:./.work/repos}")
    private String workdirRoot;

    @Value("${workdir.cleanup.retention:P3D}")
    private String retentionIso;

    /**
     * Cron expression configured in properties; this method will be invoked by Spring Scheduling.
     */
    @Scheduled(cron = "${workdir.cleanup.cron:0 0 * * * *}")
    public void scheduledCleanup() {
        if (!enabled) return;
        try {
            cleanupOnce();
        } catch (Exception e) {
            log.warn("Workdir cleanup encountered an error: {}", e.toString());
        }
    }

    public void cleanupOnce() throws IOException {
        Path root = Paths.get(workdirRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;

        Duration retention = parseDuration(retentionIso);
        Instant cutoff = Instant.now().minus(retention);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "repo-*") ) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                Long requestId = parseRequestId(name);
                if (requestId == null) continue;

                // Skip active jobs
                boolean canDelete = true;
                try {
                    BrdRequest req = brdRequestRepository.findById(requestId).orElse(null);
                    if (req != null) {
                        BrdStatus st = req.getStatus();
                        if (st != BrdStatus.COMPLETED && st != BrdStatus.FAILED) {
                            canDelete = false;
                        }
                    }
                } catch (Exception ignored) { }

                if (!canDelete) continue;

                Instant lastMod;
                try {
                    lastMod = Files.getLastModifiedTime(dir).toInstant();
                } catch (IOException e) {
                    continue;
                }
                if (lastMod.isAfter(cutoff)) continue; // still within retention window

                try {
                    log.info("Cleaning workdir for request {} at {}", requestId, dir);
                    gitIntegrationService.deleteRecursively(dir);
                } catch (Exception e) {
                    log.warn("Failed to delete {}: {}", dir, e.toString());
                }
            }
        }
    }

    private Duration parseDuration(String iso) {
        try {
            return Duration.parse(iso);
        } catch (DateTimeParseException e) {
            // support simple day spec like P3D, PT72H, or fallback 3 days
            return Duration.ofDays(3);
        }
    }

    private Long parseRequestId(String folderName) {
        try {
            String s = folderName.toLowerCase(Locale.ROOT);
            if (!s.startsWith("repo-")) return null;
            return Long.parseLong(s.substring(5));
        } catch (Exception e) {
            return null;
        }
    }
}
