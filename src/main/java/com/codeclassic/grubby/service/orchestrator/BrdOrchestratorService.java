package com.codeclassic.grubby.service.orchestrator;

import com.codeclassic.grubby.api.dto.BrdAnalysisResponse;
import com.codeclassic.grubby.api.dto.ConfluencePushRequest;
import com.codeclassic.grubby.api.dto.DashboardStatsResponse;
import com.codeclassic.grubby.api.dto.BrdListItemResponse;
import com.codeclassic.grubby.api.dto.BrdPageResponse;
import com.codeclassic.grubby.api.dto.BrdPreviewResponse;
import com.codeclassic.grubby.api.dto.BrdRefineRequest;
import com.codeclassic.grubby.api.dto.BrdSaveEditRequest;
import com.codeclassic.grubby.api.dto.BrdStatusResponse;
import com.codeclassic.grubby.api.dto.BrdVersionContentResponse;
import com.codeclassic.grubby.api.dto.BrdVersionSummary;
import com.codeclassic.grubby.api.dto.GenerateBrdRequest;
import com.codeclassic.grubby.api.dto.PushRequest;
import com.codeclassic.grubby.api.dto.PushResponse;
import com.codeclassic.grubby.domain.entity.AnalysisResult;
import com.codeclassic.grubby.domain.entity.BrdDocument;
import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import com.codeclassic.grubby.domain.entity.BrdVersion;
import com.codeclassic.grubby.domain.entity.ChangeType;
import com.codeclassic.grubby.domain.model.EndpointDescriptor;
import com.codeclassic.grubby.repository.AnalysisResultRepository;
import com.codeclassic.grubby.repository.BrdDocumentRepository;
import com.codeclassic.grubby.repository.BrdRequestRepository;
import com.codeclassic.grubby.repository.BrdRequestSpec;
import com.codeclassic.grubby.repository.BrdVersionRepository;
import com.codeclassic.grubby.service.ai.AiProcessingService;
import com.codeclassic.grubby.service.brd.BrdGeneratorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.jpa.domain.Specification;
import com.codeclassic.grubby.service.brd.BrdPreviewStore;
import com.codeclassic.grubby.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.codeclassic.grubby.service.security.RepoUrlValidator;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrdOrchestratorService {

    private static final Set<String> ALLOWED_FORMATS = Set.of("pdf", "docx", "md");

    private final BrdRequestRepository brdRequestRepository;
    private final BrdDocumentRepository brdDocumentRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final BrdVersionRepository brdVersionRepository;
    private final BrdJobRunner jobRunner;
    private final BrdPreviewStore brdPreviewStore;
    private final StorageService storageService;
    private final BrdGeneratorService brdGeneratorService;
    private final AiProcessingService aiProcessingService;
    private final ObjectMapper objectMapper;
    private final RepoUrlValidator repoUrlValidator; // P2 — SSRF guard

    @Transactional
    public Long submit(GenerateBrdRequest req, String userId) {
        // P2 — validate repoUrl against SSRF rules and DB allowlist before touching any resource
        repoUrlValidator.validate(req.getRepoUrl());

        BrdRequest entity = BrdRequest.builder()
                .userId(userId)
                .repoUrl(req.getRepoUrl())
                .branch(req.getBranch())
                .commitSha(req.getCommitSha())
                .projectType(req.getProjectType())
                .featureContext(req.getFeatureContext())
                .authType(req.getAuthType())
                .tokenRef(req.getAuthToken())
                .aiModel(req.getAiModel())
                .slackWebhookUrl(req.getSlackWebhookUrl())
                .status(BrdStatus.QUEUED)
                .stage("QUEUED")
                .progressPct(0)
                .forceReanalyze(Boolean.TRUE.equals(req.isForceReanalyze()))
                .build();
        brdRequestRepository.save(entity);
        log.info("Saved BRD request {} for user '{}'", entity.getId(), userId);

        // Fire the async job AFTER the transaction commits so the worker's findById() is
        // guaranteed to see the new row.  Without this, there is a race where the @Async
        // thread starts before the INSERT is committed and brdRequestRepository.findById()
        // returns empty, silently dropping the job.
        final Long jobId = entity.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobRunner.runAsync(jobId);
            }
        });
        return entity.getId();
    }

    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats(String userId) {
        long total     = brdRequestRepository.countByUserId(userId);
        long completed = brdRequestRepository.countByUserIdAndStatus(userId, BrdStatus.COMPLETED);
        long failed    = brdRequestRepository.countByUserIdAndStatus(userId, BrdStatus.FAILED);
        long inProgress = total - completed - failed;
        return new DashboardStatsResponse(total, completed, inProgress, failed);
    }

    @Transactional(readOnly = true)
    public BrdStatusResponse status(Long id) {
        BrdRequest request = findRequestOrThrow(id);
        return new BrdStatusResponse(
                String.valueOf(request.getId()),
                Optional.ofNullable(request.getStatus()).map(Enum::name).orElse("UNKNOWN"),
                Optional.ofNullable(request.getProgressPct()).orElse(0),
                Optional.ofNullable(request.getStage()).orElse("UNKNOWN"),
                request.getErrorMessage());
    }

    @Transactional(readOnly = true)
    public BrdPreviewResponse preview(Long id, String format) {
        BrdRequest request = findRequestOrThrow(id);
        if (request.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "BRD preview not available — job " + id + " has status " + request.getStatus());
        }
        // In-memory cache first; fall back to latest persisted version so previews survive restarts/eviction
        String markdown = brdPreviewStore.get(id)
                .filter(s -> !s.isBlank())
                .or(() -> brdVersionRepository.findFirstByRequestIdOrderByVersionNumberDesc(id)
                        .map(BrdVersion::getContent))
                .orElse("# BRD Preview\n\nContent is not yet available.");
        return new BrdPreviewResponse(String.valueOf(id), "markdown", markdown);
    }

    // ── Versioning ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BrdVersionSummary> listVersions(Long id) {
        findRequestOrThrow(id);
        return brdVersionRepository.findByRequestIdOrderByVersionNumberDesc(id)
                .stream()
                .map(this::toVersionSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BrdVersionContentResponse getVersionContent(Long id, int versionNumber) {
        findRequestOrThrow(id);
        BrdVersion version = brdVersionRepository.findByRequestIdAndVersionNumber(id, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Version " + versionNumber + " not found for request " + id));
        return toVersionContent(version);
    }

    public BrdVersionSummary saveManualEdit(Long id, String content, String userId) {
        BrdRequest req = findRequestOrThrow(id);
        if (req.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only edit a COMPLETED BRD. Current status: " + req.getStatus());
        }

        int nextVersionNumber = (int) brdVersionRepository.countByRequestId(id) + 1;
        BrdVersion version = brdVersionRepository.save(BrdVersion.builder()
                .requestId(id)
                .versionNumber(nextVersionNumber)
                .content(content)
                .changeType(ChangeType.MANUAL_EDIT)
                .createdBy(userId)
                .build());

        brdPreviewStore.put(id, content);

        try {
            regenerateDocuments(id, content);
        } catch (Exception e) {
            log.warn("Document regeneration failed for request {} after manual edit: {}", id, e.getMessage());
        }

        return toVersionSummary(version);
    }

    public BrdVersionContentResponse refineWithAi(Long id, String prompt, String userId) {
        BrdRequest req = findRequestOrThrow(id);
        if (req.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only refine a COMPLETED BRD. Current status: " + req.getStatus());
        }

        String currentContent = brdPreviewStore.get(id)
                .filter(s -> !s.isBlank())
                .or(() -> brdVersionRepository.findFirstByRequestIdOrderByVersionNumberDesc(id)
                        .map(BrdVersion::getContent))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No BRD content found for request " + id));

        String refined;
        try {
            refined = aiProcessingService.refineMarkdown(currentContent, prompt, req.getAiModel());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI refinement failed: " + e.getMessage());
        }

        int nextVersionNumber = (int) brdVersionRepository.countByRequestId(id) + 1;
        String truncatedPrompt = prompt.length() > 1000 ? prompt.substring(0, 1000) : prompt;
        BrdVersion version = brdVersionRepository.save(BrdVersion.builder()
                .requestId(id)
                .versionNumber(nextVersionNumber)
                .content(refined)
                .changeType(ChangeType.AI_REFINED)
                .changePrompt(truncatedPrompt)
                .createdBy(userId)
                .build());

        brdPreviewStore.put(id, refined);

        try {
            regenerateDocuments(id, refined);
        } catch (Exception e) {
            log.warn("Document regeneration failed for request {} after AI refinement: {}", id, e.getMessage());
        }

        return toVersionContent(version);
    }

    private void regenerateDocuments(Long requestId, String markdown) throws Exception {
        record DocEntry(String format, byte[] bytes) {}
        var docs = List.of(
                new DocEntry("md",   brdGeneratorService.renderMarkdown(markdown)),
                new DocEntry("pdf",  brdGeneratorService.renderPdf(markdown)),
                new DocEntry("docx", brdGeneratorService.renderDocx(markdown))
        );
        for (var doc : docs) {
            String key = storageService.buildBrdKey(requestId, doc.format());
            storageService.save(doc.bytes(), key);
            brdDocumentRepository.save(BrdDocument.builder()
                    .requestId(requestId)
                    .format(doc.format())
                    .storageKey(key)
                    .sizeBytes(doc.bytes().length)
                    .checksum(storageService.checksumSha256(doc.bytes()))
                    .build());
        }
    }

    private BrdVersionSummary toVersionSummary(BrdVersion v) {
        return new BrdVersionSummary(v.getId(), v.getVersionNumber(),
                v.getChangeType().name(), v.getChangePrompt(), v.getCreatedBy(), v.getCreatedAt());
    }

    private BrdVersionContentResponse toVersionContent(BrdVersion v) {
        return new BrdVersionContentResponse(v.getId(), v.getVersionNumber(),
                v.getChangeType().name(), v.getChangePrompt(), v.getCreatedBy(), v.getCreatedAt(), v.getContent());
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(Long id, String format) {
        findRequestOrThrow(id); // validates existence
        String fmt = normalizeFormat(format);
        BrdDocument doc = brdDocumentRepository
                .findFirstByRequestIdAndFormatOrderByGeneratedAtDesc(id, fmt)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not yet available in format '" + fmt + "' for request " + id));

        Resource file = storageService.load(doc.getStorageKey());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=brd-" + id + "." + fmt)
                .contentType(resolveMediaType(fmt))
                .body(file);
    }

    /**
     * Returns a paginated, optionally filtered list of all BRD requests from the DB.
     * Filtering is done via JPA Specifications — no raw JPQL or HQL involved.
     *
     * @param q            free-text query matched against repoUrl, featureContext, and id
     * @param statusFilter one of the BrdStatus enum names, "Running", "All", or null
     * @param page         0-based page index
     * @param size         page size (capped at 100)
     */
    @Transactional(readOnly = true)
    public BrdPageResponse list(String q, String statusFilter, int page, int size, String userId) {
        size = Math.min(size, 100);

        // P1 — scope to authenticated user; null userId means admin "view all"
        Specification<BrdRequest> spec = Specification
                .where(BrdRequestSpec.searchMatches(q))
                .and(BrdRequestSpec.forUser(userId));

        if (statusFilter != null && !statusFilter.isBlank() && !"All".equalsIgnoreCase(statusFilter)) {
            if ("Running".equalsIgnoreCase(statusFilter)) {
                // "Running" is a UI concept covering all non-terminal statuses
                spec = spec.and(BrdRequestSpec.isRunning());
            } else {
                try {
                    BrdStatus status = BrdStatus.valueOf(statusFilter.toUpperCase());
                    spec = spec.and(BrdRequestSpec.hasStatus(status));
                } catch (IllegalArgumentException ignored) {
                    // Unrecognised status value — treat as no filter
                }
            }
        }

        // Sort newest-first; PageRequest carries the Pageable the repo needs
        PageRequest pageable = PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("createdAt").descending());

        Page<BrdRequest> dbPage = brdRequestRepository.findAll(spec, pageable);

        List<BrdListItemResponse> items = dbPage.getContent().stream()
                .map(this::toListItem)
                .toList();

        return new BrdPageResponse(
                items,
                dbPage.getNumber(),
                dbPage.getSize(),
                dbPage.getTotalElements(),
                dbPage.getTotalPages(),
                dbPage.isLast());
    }

    private BrdListItemResponse toListItem(BrdRequest r) {
        String fc = r.getFeatureContext();
        if (fc != null && fc.length() > 120) fc = fc.substring(0, 120);
        return new BrdListItemResponse(
                String.valueOf(r.getId()),
                r.getRepoUrl(),
                r.getBranch(),
                fc,
                Optional.ofNullable(r.getStatus()).map(Enum::name).orElse("UNKNOWN"),
                Optional.ofNullable(r.getStage()).orElse("UNKNOWN"),
                Optional.ofNullable(r.getProgressPct()).orElse(0),
                r.getErrorMessage(),
                r.getCreatedAt());
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    /**
     * Returns the endpoints and relevant files discovered during code analysis for a BRD request.
     * The data was persisted by BrdJobRunner as JSON in the analysis_results table.
     */
    @Transactional(readOnly = true)
    public BrdAnalysisResponse analysis(Long id) {
        findRequestOrThrow(id); // 404 if request doesn't exist

        AnalysisResult result = analysisResultRepository
                .findTopByRequestIdOrderByIdDesc(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Analysis data not yet available for request " + id));

        List<BrdAnalysisResponse.EndpointInfo> endpoints = parseJson(
                result.getEndpointsJson(),
                new TypeReference<List<EndpointDescriptor>>() {})
                .stream()
                .map(e -> new BrdAnalysisResponse.EndpointInfo(
                        e.getHttpMethod(), e.getPath(), e.getController(), e.getMethod(), e.getSummary()))
                .toList();

        // modelsJson stores List<RelevantFile> (mapped to graphJson for notes in BrdJobRunner)
        List<BrdAnalysisResponse.RelevantFile> files = parseJson(
                result.getModelsJson(),
                new TypeReference<List<com.codeclassic.grubby.domain.model.CodeAnalysisSummary.RelevantFile>>() {})
                .stream()
                .map(f -> new BrdAnalysisResponse.RelevantFile(f.getPath(), f.getScore(), f.getFirstLines()))
                .toList();

        List<String> notes = parseJson(result.getGraphJson(), new TypeReference<>() {});

        return new BrdAnalysisResponse(String.valueOf(id), endpoints, files, notes);
    }

    // ── Integration Push ─────────────────────────────────────────────────────

    /**
     * Pushes the BRD as a Story/Epic to JIRA Cloud using the REST v3 API.
     * Creates one issue per BRD with the markdown preview as the description.
     */
    public PushResponse pushToJira(Long id, PushRequest req) {
        BrdRequest brd = findRequestOrThrow(id);
        if (brd.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only push a COMPLETED BRD to JIRA. Current status: " + brd.getStatus());
        }

        String markdown = brdPreviewStore.get(id).orElse("See attached BRD document.");
        String title    = buildTitle(brd);

        try {
            String baseUrl = req.getJiraUrl().replaceAll("/+$", "");
            String token   = Base64.getEncoder().encodeToString(
                    (req.getJiraEmail() + ":" + req.getJiraApiToken()).getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Basic " + token);

            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("project",     Map.of("key", req.getJiraProjectKey()));
            fields.put("summary",     title);
            fields.put("issuetype",   Map.of("name", "Story"));
            fields.put("description", Map.of(
                    "type", "doc",
                    "version", 1,
                    "content", List.of(Map.of(
                            "type", "paragraph",
                            "content", List.of(Map.of(
                                    "type", "text",
                                    "text", truncate(markdown, 32000)))))));
            if (req.getEpicKey() != null && !req.getEpicKey().isBlank()) {
                fields.put("parent", Map.of("key", req.getEpicKey()));
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("fields", fields), headers);
            RestTemplate rt = new RestTemplate();
            ResponseEntity<Map> response = rt.exchange(
                    baseUrl + "/rest/api/3/issue", HttpMethod.POST, entity, Map.class);

            String issueKey = (String) response.getBody().get("key");
            String issueUrl = baseUrl + "/browse/" + issueKey;
            log.info("Pushed BRD {} to JIRA as {}", id, issueKey);
            return new PushResponse(true, issueKey, issueUrl, "Created JIRA story " + issueKey);

        } catch (Exception e) {
            log.warn("JIRA push failed for BRD {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "JIRA push failed: " + e.getMessage());
        }
    }

    /**
     * Pushes the BRD as an Issue to Linear using the GraphQL API.
     */
    public PushResponse pushToLinear(Long id, PushRequest req) {
        BrdRequest brd = findRequestOrThrow(id);
        if (brd.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only push a COMPLETED BRD to Linear. Current status: " + brd.getStatus());
        }

        String markdown = brdPreviewStore.get(id).orElse("See attached BRD document.");
        String title    = buildTitle(brd);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", req.getLinearApiKey());

            String mutation = """
                    mutation CreateIssue($title: String!, $description: String!, $teamId: String!) {
                      issueCreate(input: { title: $title, description: $description, teamId: $teamId }) {
                        success
                        issue { id url identifier }
                      }
                    }""";

            Map<String, Object> variables = Map.of(
                    "title",       title,
                    "description", truncate(markdown, 65000),
                    "teamId",      req.getLinearTeamId());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                    Map.of("query", mutation, "variables", variables), headers);
            RestTemplate rt = new RestTemplate();
            ResponseEntity<Map> response = rt.exchange(
                    "https://api.linear.app/graphql", HttpMethod.POST, entity, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> data        = (Map<String, Object>) response.getBody().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> issueCreate = (Map<String, Object>) data.get("issueCreate");
            @SuppressWarnings("unchecked")
            Map<String, Object> issue       = (Map<String, Object>) issueCreate.get("issue");

            String issueId  = (String) issue.get("identifier");
            String issueUrl = (String) issue.get("url");
            log.info("Pushed BRD {} to Linear as {}", id, issueId);
            return new PushResponse(true, issueId, issueUrl, "Created Linear issue " + issueId);

        } catch (Exception e) {
            log.warn("Linear push failed for BRD {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Linear push failed: " + e.getMessage());
        }
    }

    // ── Confluence Push ───────────────────────────────────────────────────────

    /**
     * Creates or updates a Confluence page with the BRD content in Confluence storage format.
     */
    @SuppressWarnings("unchecked")
    public PushResponse pushToConfluence(Long id, ConfluencePushRequest req) {
        BrdRequest brd = findRequestOrThrow(id);
        if (brd.getStatus() != BrdStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only push a COMPLETED BRD to Confluence. Current status: " + brd.getStatus());
        }

        String markdown = brdPreviewStore.get(id).orElse("See attached BRD document.");
        String title = req.getPageTitle() != null && !req.getPageTitle().isBlank()
                ? req.getPageTitle() : buildTitle(brd);
        String confluenceHtml = markdownToConfluenceStorage(markdown);

        try {
            String baseUrl = req.getConfluenceUrl().replaceAll("/+$", "");
            String token = Base64.getEncoder().encodeToString(
                    (req.getEmail() + ":" + req.getApiToken()).getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Basic " + token);

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("type", "page");
            body.put("title", title);
            body.put("space", Map.of("key", req.getSpaceKey()));
            body.put("body", Map.of("storage", Map.of(
                    "value", confluenceHtml,
                    "representation", "storage")));
            if (req.getParentPageId() != null && !req.getParentPageId().isBlank()) {
                body.put("ancestors", List.of(Map.of("id", req.getParentPageId())));
            }

            RestTemplate rt = new RestTemplate();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = rt.exchange(
                    baseUrl + "/rest/api/content", HttpMethod.POST, entity, Map.class);

            String pageId  = (String) response.getBody().get("id");
            String pageUrl = baseUrl + "/pages/" + pageId;
            log.info("Pushed BRD {} to Confluence as page {}", id, pageId);
            return new PushResponse(true, pageId, pageUrl, "Created Confluence page: " + title);

        } catch (Exception e) {
            log.warn("Confluence push failed for BRD {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Confluence push failed: " + e.getMessage());
        }
    }

    /** Minimal Markdown → Confluence storage format conversion. */
    private String markdownToConfluenceStorage(String markdown) {
        if (markdown == null) return "";
        return "<p>" + markdown
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("(?m)^### (.+)$", "</p><h3>$1</h3><p>")
                .replaceAll("(?m)^## (.+)$", "</p><h2>$1</h2><p>")
                .replaceAll("(?m)^# (.+)$", "</p><h1>$1</h1><p>")
                .replaceAll("(?m)^---$", "</p><hr/><p>")
                .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("\\*(.+?)\\*", "<em>$1</em>")
                .replaceAll("`(.+?)`", "<code>$1</code>")
                .replace("\n\n", "</p><p>")
                .replace("\n", "<br/>")
                + "</p>";
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    private BrdRequest findRequestOrThrow(Long id) {
        return brdRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "BRD request not found: " + id));
    }

    private <T> List<T> parseJson(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSON field: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildTitle(BrdRequest brd) {
        if (brd.getFeatureContext() != null && !brd.getFeatureContext().isBlank()) {
            String fc = brd.getFeatureContext().trim();
            return fc.length() > 120 ? fc.substring(0, 120) + "…" : fc;
        }
        try {
            return "BRD: " + new java.net.URL(brd.getRepoUrl()).getPath().replace("/", " ").trim();
        } catch (Exception e) {
            return "BRD #" + brd.getId();
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : (s != null ? s : "");
    }

    private String normalizeFormat(String format) {
        if (format == null) return "pdf";
        String f = format.trim().toLowerCase();
        return ALLOWED_FORMATS.contains(f) ? f : "pdf";
    }

    private MediaType resolveMediaType(String fmt) {
        return switch (fmt) {
            case "pdf"  -> MediaType.APPLICATION_PDF;
            case "docx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "md"   -> MediaType.parseMediaType("text/markdown; charset=UTF-8");
            default     -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
