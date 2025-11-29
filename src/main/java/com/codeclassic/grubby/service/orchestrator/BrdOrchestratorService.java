package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.api.dto.BrdPreviewResponse;
import com.codeclassic.grubby.api.dto.BrdStatusResponse;
import com.codeclassic.grubby.api.dto.GenerateBrdRequest;
import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import com.codeclassic.grubby.domain.entity.BrdDocument;
import com.codeclassic.grubby.repository.BrdRequestRepository;
import com.codeclassic.grubby.repository.BrdDocumentRepository;
import com.codeclassic.grubby.service.brd.BrdPreviewStore;
import com.codeclassic.grubby.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrdOrchestratorService {

    private final BrdRequestRepository brdRequestRepository;
    private final BrdJobRunner jobRunner;
    private final BrdPreviewStore brdPreviewStore;
    private final BrdDocumentRepository brdDocumentRepository;
    private final StorageService storageService;

    @Transactional
    public Long submit(GenerateBrdRequest req, String userId) {
        BrdRequest entity = BrdRequest.builder()
                .userId(userId)
                .repoUrl(req.getRepoUrl())
                .branch(req.getBranch())
                .commitSha(req.getCommitSha())
                .projectType(req.getProjectType())
                .featureContext(req.getFeatureContext())
                .authType(req.getAuthType())
                .tokenRef(req.getAuthToken())
                .status(BrdStatus.QUEUED)
                .stage("QUEUED")
                .progressPct(0)
                .forceReanalyze(Boolean.TRUE.equals(req.isForceReanalyze()))
                .build();
        log.info("Submitting BRD request: {}", entity);
        brdRequestRepository.save(entity);
        jobRunner.runAsync(entity.getId());
        return entity.getId();
    }

    @Transactional(readOnly = true)
    public BrdStatusResponse status(Long id) {
        log.info("Checking status for BRD request: {}", id);
        BrdRequest request = brdRequestRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("BRD request not found: {}", id);
                    return new IllegalArgumentException("Request not found");
                });
        BrdStatusResponse response = new BrdStatusResponse(
                String.valueOf(request.getId()),
                Optional.ofNullable(request.getStatus()).map(Enum::name).orElse("UNKNOWN"),
                Optional.ofNullable(request.getProgressPct()).orElse(0),
                Optional.ofNullable(request.getStage()).orElse("UNKNOWN"),
                request.getErrorMessage()
        );
        log.info("Status for BRD request {}: status={}, progress={}, stage={}",
                id, response.getStatus(), response.getProgressPct(), response.getStage());
        return response;
    }

    @Transactional(readOnly = true)
    public BrdPreviewResponse preview(Long id, String format) {
        log.info("Preview request for BRD {} in format: {}", id, format);
        String markdown = brdPreviewStore.get(id).orElse(null);
        if (markdown == null || markdown.isBlank()) {
            markdown = "# BRD Preview\n\nGeneration in progress or no content available yet.";
        }
        // For now we always return markdown regardless of requested format.
        return new BrdPreviewResponse(String.valueOf(id), "markdown", markdown);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(Long id, String format) {
        log.info("Processing download request for BRD {} in format: {}", id, format);
        String fmt = (format == null ? "pdf" : format.trim().toLowerCase());
        if (!(fmt.equals("pdf") || fmt.equals("docx") || fmt.equals("md"))) {
            fmt = "pdf";
        }
        Optional<BrdDocument> docOpt = brdDocumentRepository.findFirstByRequestIdAndFormatOrderByGeneratedAtDesc(id, fmt);
        if (docOpt.isEmpty()) {
            // if not found and requesting pdf/docx, but md exists, inform user
            return ResponseEntity.notFound().build();
        }
        BrdDocument doc = docOpt.get();
        Resource file = storageService.load(doc.getStorageKey());
        String filename = "brd-" + id + "." + fmt;
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (fmt.equals("pdf")) contentType = MediaType.APPLICATION_PDF;
        else if (fmt.equals("docx")) contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        else if (fmt.equals("md")) contentType = MediaType.parseMediaType("text/markdown; charset=UTF-8");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(contentType)
                .body(file);
    }
}