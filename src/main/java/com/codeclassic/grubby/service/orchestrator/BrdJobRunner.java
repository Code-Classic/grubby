package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.domain.entity.AnalysisResult;
import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import com.codeclassic.grubby.domain.model.CodeAnalysisSummary;
import com.codeclassic.grubby.repository.AnalysisResultRepository;
import com.codeclassic.grubby.repository.BrdRequestRepository;
import com.codeclassic.grubby.service.ai.AiProcessingService;
import com.codeclassic.grubby.service.analysis.RepoFileWalker;
import com.codeclassic.grubby.service.analysis.SimpleCodeAnalyzer;
import com.codeclassic.grubby.service.analysis.CodeAnalyzerService;
import com.codeclassic.grubby.service.brd.BrdPreviewStore;
import com.codeclassic.grubby.service.brd.BrdGeneratorService;
import com.codeclassic.grubby.service.git.GitIntegrationService;
import com.codeclassic.grubby.service.storage.StorageService;
import com.codeclassic.grubby.repository.BrdDocumentRepository;
import com.codeclassic.grubby.domain.entity.BrdDocument;
import com.codeclassic.grubby.service.cache.InMemoryCacheService;
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

@Component
@RequiredArgsConstructor
public class BrdJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BrdJobRunner.class);

    private final BrdRequestRepository brdRequestRepository;
    private final AnalysisResultRepository analysisResultRepository;

    private final GitIntegrationService gitIntegrationService;
    private final RepoFileWalker repoFileWalker;
    private final SimpleCodeAnalyzer codeAnalyzerHeuristic;
    private final CodeAnalyzerService codeAnalyzerAst;

    private final AiProcessingService aiProcessingService;
    private final BrdPreviewStore brdPreviewStore;
    private final BrdGeneratorService brdGeneratorService;
    private final StorageService storageService;
    private final BrdDocumentRepository brdDocumentRepository;
    private final InMemoryCacheService cacheService;

    @Value("${analysis.engine:javaparser}")
    private String analysisEngine;

    @Value("${workdir.deleteOnSuccess:false}")
    private boolean deleteOnSuccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void runAsync(Long requestId) {
        run(requestId);
    }

    @Transactional
    public void run(Long requestId) {
        Optional<BrdRequest> opt = brdRequestRepository.findById(requestId);
        if (opt.isEmpty()) {
            log.warn("BRD request {} not found", requestId);
            return;
        }
        BrdRequest req = opt.get();

        // Compute cache keys
        String ref = (req.getCommitSha() != null && !req.getCommitSha().isBlank()) ? req.getCommitSha() :
                ((req.getBranch() != null && !req.getBranch().isBlank()) ? req.getBranch() : "HEAD");
        String repoHash = HashUtils.sha1((req.getRepoUrl() == null ? "" : req.getRepoUrl()) + "@" + ref);
        String featureHash = HashUtils.sha1((req.getFeatureContext() == null ? "" : req.getFeatureContext().trim()));
        String analysisCacheKey = "analysis:" + repoHash;
        String brdCacheKey = "brd:" + repoHash + ":" + featureHash;

        Path repoPath = null;
        try {
            // If BRD cached and not forcing, short-circuit to artifact generation
            if (!req.isForceReanalyze()) {
                var cachedMd = cacheService.getBrd(brdCacheKey);
                if (cachedMd.isPresent()) {
                    String markdown = cachedMd.get();
                    brdPreviewStore.put(req.getId(), markdown);
                    // Generate and store artifacts from cached markdown
                    update(req, BrdStatus.FORMATTING_DOCUMENT, "FORMATTING_DOCUMENT", 85);
                    byte[] mdBytes = brdGeneratorService.renderMarkdown(markdown);
                    byte[] pdfBytes = brdGeneratorService.renderPdf(markdown);
                    byte[] docxBytes = brdGeneratorService.renderDocx(markdown);

                    update(req, BrdStatus.STORING_OUTPUT, "STORING_OUTPUT", 95);
                    String mdKey = storageService.buildBrdKey(req.getId(), "md");
                    String pdfKey = storageService.buildBrdKey(req.getId(), "pdf");
                    String docxKey = storageService.buildBrdKey(req.getId(), "docx");
                    storageService.save(mdBytes, mdKey);
                    storageService.save(pdfBytes, pdfKey);
                    storageService.save(docxBytes, docxKey);

                    brdDocumentRepository.save(BrdDocument.builder().requestId(req.getId()).format("md").storageKey(mdKey).sizeBytes(mdBytes.length).checksum(storageService.checksumSha256(mdBytes)).build());
                    brdDocumentRepository.save(BrdDocument.builder().requestId(req.getId()).format("pdf").storageKey(pdfKey).sizeBytes(pdfBytes.length).checksum(storageService.checksumSha256(pdfBytes)).build());
                    brdDocumentRepository.save(BrdDocument.builder().requestId(req.getId()).format("docx").storageKey(docxKey).sizeBytes(docxBytes.length).checksum(storageService.checksumSha256(docxBytes)).build());

                    update(req, BrdStatus.COMPLETED, "COMPLETED", 100);
                    try {
                        if (deleteOnSuccess && repoPath != null) {
                            gitIntegrationService.deleteRecursively(repoPath);
                        }
                    } catch (Exception cleanupEx) {
                        log.warn("Cleanup after success failed for {}: {}", req.getId(), cleanupEx.toString());
                    }
                    return;
                }
            }

            CodeAnalysisSummary summary = null;
            if (!req.isForceReanalyze()) {
                summary = cacheService.getAnalysis(analysisCacheKey, CodeAnalysisSummary.class).orElse(null);
            }

            if (summary == null) {
                // 1) Clone (only when needed to compute analysis)
                update(req, BrdStatus.CLONING_REPO, "CLONING_REPO", 10);
                repoPath = gitIntegrationService.cloneRepo(req.getId(), req.getRepoUrl(), req.getBranch(), req.getCommitSha(), req.getTokenRef());

                // 2) Walk files
                update(req, BrdStatus.ANALYZING_CODE, "ANALYZING_CODE", 40);
                List<Path> javaFiles = repoFileWalker.listJavaFiles(repoPath);

                // 3) Analyze
                if ("javaparser".equalsIgnoreCase(analysisEngine)) {
                    try {
                        summary = codeAnalyzerAst.analyze(repoPath, javaFiles, req.getFeatureContext(), repoFileWalker);
                    } catch (Exception e) {
                        log.warn("AST analyzer failed, falling back to heuristic: {}", e.getMessage());
                        summary = codeAnalyzerHeuristic.analyze(repoPath, javaFiles, req.getFeatureContext(), repoFileWalker);
                    }
                } else {
                    summary = codeAnalyzerHeuristic.analyze(repoPath, javaFiles, req.getFeatureContext(), repoFileWalker);
                }

                // Cache analysis summary
                cacheService.putAnalysis(analysisCacheKey, summary);

                // Persist analysis (store endpoints + relevant files as JSON)
                String endpointsJson = objectMapper.writeValueAsString(summary.getEndpoints());
                String modelsJson = objectMapper.writeValueAsString(summary.getRelevantFiles());
                String notesJson = objectMapper.writeValueAsString(summary.getNotes());
                AnalysisResult ar = AnalysisResult.builder()
                        .requestId(req.getId())
                        .repoHash(req.getRepoUrl())
                        .endpointsJson(endpointsJson)
                        .modelsJson(modelsJson)
                        .servicesJson(null)
                        .graphJson(notesJson)
                        .build();
                analysisResultRepository.save(ar);
            }

            // 5) AI generation using (maybe cached) summary
            update(req, BrdStatus.GENERATING_AI_TEXT, "GENERATING_AI_TEXT", 70);
            String markdown = aiProcessingService.generateMarkdown(req.getRepoUrl(), req.getFeatureContext(), summary);
            cacheService.putBrd(brdCacheKey, markdown);
            brdPreviewStore.put(req.getId(), markdown);

            // 6) Finalize: generate artifacts
            update(req, BrdStatus.FORMATTING_DOCUMENT, "FORMATTING_DOCUMENT", 85);
            byte[] mdBytes = brdGeneratorService.renderMarkdown(markdown);
            byte[] pdfBytes = brdGeneratorService.renderPdf(markdown);
            byte[] docxBytes = brdGeneratorService.renderDocx(markdown);

            // 7) Store artifacts
            update(req, BrdStatus.STORING_OUTPUT, "STORING_OUTPUT", 95);
            String mdKey = storageService.buildBrdKey(req.getId(), "md");
            String pdfKey = storageService.buildBrdKey(req.getId(), "pdf");
            String docxKey = storageService.buildBrdKey(req.getId(), "docx");
            storageService.save(mdBytes, mdKey);
            storageService.save(pdfBytes, pdfKey);
            storageService.save(docxBytes, docxKey);

            BrdDocument mdDoc = BrdDocument.builder()
                    .requestId(req.getId())
                    .format("md")
                    .storageKey(mdKey)
                    .sizeBytes(mdBytes.length)
                    .checksum(storageService.checksumSha256(mdBytes))
                    .build();
            brdDocumentRepository.save(mdDoc);
            BrdDocument pdfDoc = BrdDocument.builder()
                    .requestId(req.getId())
                    .format("pdf")
                    .storageKey(pdfKey)
                    .sizeBytes(pdfBytes.length)
                    .checksum(storageService.checksumSha256(pdfBytes))
                    .build();
            brdDocumentRepository.save(pdfDoc);
            BrdDocument docxDoc = BrdDocument.builder()
                    .requestId(req.getId())
                    .format("docx")
                    .storageKey(docxKey)
                    .sizeBytes(docxBytes.length)
                    .checksum(storageService.checksumSha256(docxBytes))
                    .build();
            brdDocumentRepository.save(docxDoc);

            update(req, BrdStatus.COMPLETED, "COMPLETED", 100);
        } catch (Exception ex) {
            log.error("Job {} failed", requestId, ex);
            req.setStatus(BrdStatus.FAILED);
            req.setStage("FAILED");
            // Preserve last progress and capture error message
            String message = ex.getMessage();
            if (message != null && message.length() > 2000) {
                message = message.substring(0, 2000);
            }
            req.setErrorMessage(message);
            brdRequestRepository.save(req);
        }
    }

    private void update(BrdRequest req, BrdStatus status, String stage, int pct) {
        req.setStatus(status);
        req.setStage(stage);
        req.setProgressPct(pct);
        brdRequestRepository.save(req);
    }
}
