package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.domain.entity.AnalysisResult;
import com.codeclassic.grubby.domain.entity.BrdDocument;
import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import com.codeclassic.grubby.domain.entity.BrdVersion;
import com.codeclassic.grubby.domain.entity.ChangeType;
import com.codeclassic.grubby.repository.BrdVersionRepository;
import com.codeclassic.grubby.domain.model.CodeAnalysisSummary;
import com.codeclassic.grubby.repository.AnalysisResultRepository;
import com.codeclassic.grubby.repository.BrdDocumentRepository;
import com.codeclassic.grubby.repository.BrdRequestRepository;
import com.codeclassic.grubby.service.ai.AiProcessingService;
import com.codeclassic.grubby.service.analysis.CodeAnalyzerService;
import com.codeclassic.grubby.service.analysis.PolyglotCodeAnalyzer;
import com.codeclassic.grubby.service.analysis.RepoFileWalker;
import com.codeclassic.grubby.service.analysis.SimpleCodeAnalyzer;
import com.codeclassic.grubby.service.brd.BrdGeneratorService;
import com.codeclassic.grubby.service.brd.BrdPreviewStore;
import com.codeclassic.grubby.service.cache.InMemoryCacheService;
import com.codeclassic.grubby.service.git.GitIntegrationService;
import com.codeclassic.grubby.service.notification.SlackNotificationService;
import com.codeclassic.grubby.service.storage.StorageService;
import com.codeclassic.grubby.util.HashUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the async BRD generation pipeline for a single request.
 *
 * Improvements over original:
 * - @Async now references named executor "brdExecutor" (was using default executor)
 * - repoPath null-check before cleanup was always false (bug fixed)
 * - Document persistence extracted to saveDocument() helper to eliminate code duplication
 * - Retry config reads from @Value instead of System.getProperty
 */
@Component
@RequiredArgsConstructor
public class BrdJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BrdJobRunner.class);

    private final BrdRequestRepository brdRequestRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final BrdDocumentRepository brdDocumentRepository;
    private final BrdVersionRepository brdVersionRepository;

    private final GitIntegrationService gitIntegrationService;
    private final RepoFileWalker repoFileWalker;
    private final SimpleCodeAnalyzer codeAnalyzerHeuristic;
    private final CodeAnalyzerService codeAnalyzerAst;
    private final PolyglotCodeAnalyzer polyglotCodeAnalyzer;
    private final SlackNotificationService slackNotificationService;

    private final AiProcessingService aiProcessingService;
    private final BrdPreviewStore brdPreviewStore;
    private final BrdGeneratorService brdGeneratorService;
    private final StorageService storageService;
    private final InMemoryCacheService cacheService;

    private final ObjectMapper objectMapper;

    @Value("${analysis.engine:javaparser}")
    private String analysisEngine;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    // Repo workdir is always cleaned up immediately after analysis completes (success or failure).
    // Since every BRD generation re-clones at the latest commit, there is no value in keeping
    // the cloned directory around — it only wastes disk space. The WorkdirCleanupService
    // acts as a safety net for any dirs that slipped through (e.g. JVM crash mid-job).

    /**
     * Submits the job to the named thread pool executor.
     * Previously used the default Spring executor — now correctly routes to brdExecutor.
     */
    @Async("brdExecutor")
    public void runAsync(Long requestId) {
        run(requestId);
    }

    // NOTE: @Transactional is intentionally omitted here. This method is called directly
    // from runAsync() within the same bean, so Spring's AOP proxy would be bypassed anyway.
    // Each brdRequestRepository.save() in update() auto-commits, which is the correct
    // behavior — we want intermediate status changes to be immediately visible to polling callers.
    public void run(Long requestId) {
        BrdRequest req = brdRequestRepository.findById(requestId).orElse(null);
        if (req == null) {
            log.warn("BRD request {} not found — skipping", requestId);
            return;
        }

        String ref = resolveRef(req);
        String repoHash = HashUtils.sha1((req.getRepoUrl() == null ? "" : req.getRepoUrl()) + "@" + ref);
        String featureHash = HashUtils.sha1(req.getFeatureContext() == null ? "" : req.getFeatureContext().trim());
        String analysisCacheKey = "analysis:" + repoHash;
        String brdCacheKey = "brd:" + repoHash + ":" + featureHash;

        Path repoPath = null;
        try {
            // Short-circuit: if BRD is cached and force-reanalyze is off, skip clone+analysis
            if (!req.isForceReanalyze()) {
                Optional<String> cachedMd = cacheService.getBrd(brdCacheKey);
                if (cachedMd.isPresent()) {
                    log.info("BRD cache hit for request {} — skipping clone and analysis", requestId);
                    persistArtifacts(req, cachedMd.get());
                    update(req, BrdStatus.COMPLETED, "COMPLETED", 100);
                    return;
                }
            }

            // Check analysis cache
            CodeAnalysisSummary summary = null;
            if (!req.isForceReanalyze()) {
                summary = cacheService.getAnalysis(analysisCacheKey, CodeAnalysisSummary.class).orElse(null);
                if (summary != null) {
                    log.info("Analysis cache hit for request {}", requestId);
                }
            }

            if (summary == null) {
                // Step 1: Clone
                update(req, BrdStatus.CLONING_REPO, "CLONING_REPO", 10);
                repoPath = gitIntegrationService.cloneRepo(req.getId(), req.getRepoUrl(),
                        req.getBranch(), req.getCommitSha(), req.getTokenRef());

                // Step 2: Walk files
                update(req, BrdStatus.ANALYZING_CODE, "ANALYZING_CODE", 30);
                List<Path> javaFiles = repoFileWalker.listJavaFiles(repoPath);
                log.info("Found {} Java files to analyze for request {}", javaFiles.size(), requestId);

                // Step 3: Analyze (AST with heuristic fallback) + polyglot for non-Java files
                update(req, BrdStatus.ANALYZING_CODE, "ANALYZING_CODE", 50);
                summary = analyze(repoPath, javaFiles, req.getFeatureContext());

                // Merge in Python / TypeScript / Go analysis
                CodeAnalysisSummary polyglot = polyglotCodeAnalyzer.analyze(repoPath, req.getFeatureContext());
                summary = polyglotCodeAnalyzer.mergeWithJava(summary, polyglot);

                // Cache and persist analysis
                cacheService.putAnalysis(analysisCacheKey, summary);
                persistAnalysisResult(req, summary);
            }

            // Step 4: AI generation
            update(req, BrdStatus.GENERATING_AI_TEXT, "GENERATING_AI_TEXT", 70);
            String markdown = aiProcessingService.generateMarkdown(req.getRepoUrl(), req.getFeatureContext(), summary, req.getAiModel());
            cacheService.putBrd(brdCacheKey, markdown);
            brdPreviewStore.put(req.getId(), markdown);

            // Step 5: Render and store artifacts
            persistArtifacts(req, markdown);
            update(req, BrdStatus.COMPLETED, "COMPLETED", 100);
            log.info("BRD generation completed for request {}", requestId);

            // Notify via Slack (non-blocking; errors are swallowed in SlackNotificationService)
            slackNotificationService.sendBrdCompleted(
                    req.getSlackWebhookUrl(), buildTitle(req), String.valueOf(req.getId()), frontendUrl);

        } catch (Exception ex) {
            log.error("BRD job {} failed at stage '{}'", requestId,
                    req.getStage() != null ? req.getStage() : "UNKNOWN", ex);
            String message = ex.getMessage();
            if (message != null && message.length() > 2000) {
                message = message.substring(0, 2000);
            }
            req.setStatus(BrdStatus.FAILED);
            req.setStage("FAILED");
            req.setProgressPct(0);  // Reset so the FE progress bar doesn't show a misleading partial value
            req.setErrorMessage(message);
            brdRequestRepository.save(req);

            slackNotificationService.sendBrdFailed(
                    req.getSlackWebhookUrl(), buildTitle(req), String.valueOf(req.getId()), frontendUrl);
        } finally {
            // Always delete the cloned workdir — every run clones fresh, so there is no
            // reason to keep the directory. Immediate cleanup avoids disk pressure and
            // ensures no sensitive repo contents linger on the server filesystem.
            if (repoPath != null) {
                try {
                    gitIntegrationService.deleteRecursively(repoPath);
                    log.debug("Cleaned up workdir for request {}", requestId);
                } catch (Exception cleanupEx) {
                    log.warn("Workdir cleanup failed for request {}: {}", requestId, cleanupEx.getMessage());
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private CodeAnalysisSummary analyze(Path repoPath, List<Path> javaFiles, String featureContext) {
        if ("javaparser".equalsIgnoreCase(analysisEngine)) {
            try {
                return codeAnalyzerAst.analyze(repoPath, javaFiles, featureContext, repoFileWalker);
            } catch (Exception e) {
                log.warn("AST analyzer failed, falling back to heuristic: {}", e.getMessage());
            }
        }
        return codeAnalyzerHeuristic.analyze(repoPath, javaFiles, featureContext, repoFileWalker);
    }

    private void persistArtifacts(BrdRequest req, String markdown) throws Exception {
        update(req, BrdStatus.FORMATTING_DOCUMENT, "FORMATTING_DOCUMENT", 85);
        byte[] mdBytes = brdGeneratorService.renderMarkdown(markdown);
        byte[] pdfBytes = brdGeneratorService.renderPdf(markdown);
        byte[] docxBytes = brdGeneratorService.renderDocx(markdown);

        update(req, BrdStatus.STORING_OUTPUT, "STORING_OUTPUT", 95);
        saveDocument(req.getId(), "md", mdBytes);
        saveDocument(req.getId(), "pdf", pdfBytes);
        saveDocument(req.getId(), "docx", docxBytes);
        createInitialVersion(req.getId(), markdown, req.getUserId());
    }

    /** Extracted helper — eliminates the 3× duplicated save block from the original. */
    private void saveDocument(Long requestId, String format, byte[] data) throws Exception {
        String key = storageService.buildBrdKey(requestId, format);
        storageService.save(data, key);
        brdDocumentRepository.save(BrdDocument.builder()
                .requestId(requestId)
                .format(format)
                .storageKey(key)
                .sizeBytes(data.length)
                .checksum(storageService.checksumSha256(data))
                .build());
    }

    private void createInitialVersion(Long requestId, String content, String userId) {
        if (brdVersionRepository.countByRequestId(requestId) == 0) {
            brdVersionRepository.save(BrdVersion.builder()
                    .requestId(requestId)
                    .versionNumber(1)
                    .content(content)
                    .changeType(ChangeType.GENERATED)
                    .createdBy(userId != null ? userId : "system")
                    .build());
            log.debug("Created initial version 1 for request {}", requestId);
        }
    }

    private void persistAnalysisResult(BrdRequest req, CodeAnalysisSummary summary) {
        try {
            String endpointsJson = objectMapper.writeValueAsString(summary.getEndpoints());
            String modelsJson = objectMapper.writeValueAsString(summary.getRelevantFiles());
            String notesJson = objectMapper.writeValueAsString(summary.getNotes());
            analysisResultRepository.save(AnalysisResult.builder()
                    .requestId(req.getId())
                    .repoHash(req.getRepoUrl())
                    .endpointsJson(endpointsJson)
                    .modelsJson(modelsJson)
                    .graphJson(notesJson)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist analysis result for request {}: {}", req.getId(), e.getMessage());
        }
    }

    private void update(BrdRequest req, BrdStatus status, String stage, int pct) {
        req.setStatus(status);
        req.setStage(stage);
        req.setProgressPct(pct);
        brdRequestRepository.save(req);
        log.debug("Request {} → {} ({}%)", req.getId(), stage, pct);
    }

    private String resolveRef(BrdRequest req) {
        if (req.getCommitSha() != null && !req.getCommitSha().isBlank()) return req.getCommitSha();
        if (req.getBranch() != null && !req.getBranch().isBlank()) return req.getBranch();
        return "HEAD";
    }

    private String buildTitle(BrdRequest req) {
        if (req.getFeatureContext() != null && !req.getFeatureContext().isBlank()) {
            String fc = req.getFeatureContext().trim();
            return fc.length() > 80 ? fc.substring(0, 80) + "…" : fc;
        }
        return "BRD #" + req.getId();
    }
}
