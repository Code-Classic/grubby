package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.*;
import com.codeclassic.grubby.service.orchestrator.BrdOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/brd")
@RequiredArgsConstructor
// P7 — @CrossOrigin removed; CORS is now handled globally by SecurityConfig.corsConfigurationSource()
@Tag(name = "BRD", description = "Business Requirements Document generation endpoints")
public class BrdController {

    private final BrdOrchestratorService orchestratorService;

    @Operation(summary = "List all BRD jobs",
            description = "Returns a paginated list of all BRD requests, optionally filtered by status and search query.")
    @ApiResponse(responseCode = "200", description = "Page of BRD jobs returned")
    @GetMapping
    public ResponseEntity<BrdPageResponse> list(
            @RequestParam(value = "q",      required = false, defaultValue = "")    String q,
            @RequestParam(value = "status", required = false, defaultValue = "All") String status,
            @RequestParam(value = "page",   required = false, defaultValue = "0")   int page,
            @RequestParam(value = "size",   required = false, defaultValue = "20")  int size,
            // P1 — list is scoped to the calling user; admins pass ?all=true to see everything
            @RequestParam(value = "all",    required = false, defaultValue = "false") boolean all,
            @AuthenticationPrincipal UserDetails principal) {

        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String userFilter = (all && isAdmin) ? null : (principal != null ? principal.getUsername() : null);
        return ResponseEntity.ok(orchestratorService.list(q, status, page, size, userFilter));
    }

    @Operation(summary = "Get dashboard stats for the authenticated user")
    @ApiResponse(responseCode = "200", description = "Stats returned")
    @GetMapping("/stats")
    public ResponseEntity<DashboardStatsResponse> stats(
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal != null ? principal.getUsername() : "anonymous";
        return ResponseEntity.ok(orchestratorService.getDashboardStats(userId));
    }

    @Operation(summary = "Submit a new BRD generation job",
            description = "Accepts a repository URL and optional context, queues an async job, and returns the job ID.")
    @ApiResponse(responseCode = "202", description = "Job accepted and queued")
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @PostMapping("/generate")
    public ResponseEntity<GenerateBrdResponse> generate(
            @Valid @RequestBody GenerateBrdRequest request,
            // P1 — userId comes from the authenticated JWT principal, not an unauthenticated header
            @AuthenticationPrincipal UserDetails principal) {

        String resolvedUserId = (principal != null) ? principal.getUsername() : "anonymous";
        log.info("Generate BRD request received from user '{}' for repo: {}", resolvedUserId, request.getRepoUrl());
        Long id = orchestratorService.submit(request, resolvedUserId);
        return ResponseEntity.accepted()
                .body(new GenerateBrdResponse(id.toString(), "QUEUED", "Job accepted and queued for processing"));
    }

    @Operation(summary = "Get the status of a BRD generation job")
    @ApiResponse(responseCode = "200", description = "Status returned")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @GetMapping("/{id}/status")
    public ResponseEntity<BrdStatusResponse> status(
            @Parameter(description = "Job ID returned from /generate") @PathVariable("id") Long id) {
        return ResponseEntity.ok(orchestratorService.status(id));
    }

    @Operation(summary = "Preview the generated BRD content")
    @ApiResponse(responseCode = "200", description = "Preview content returned")
    @ApiResponse(responseCode = "404", description = "Job not found or not yet complete")
    @GetMapping("/{id}/preview")
    public ResponseEntity<BrdPreviewResponse> preview(
            @PathVariable("id") Long id,
            @RequestParam(value = "format", defaultValue = "markdown") String format) {
        return ResponseEntity.ok(orchestratorService.preview(id, format));
    }

    @Operation(summary = "Download the generated BRD document",
            description = "Returns the document as a file attachment. Supported formats: pdf, docx, md.")
    @ApiResponse(responseCode = "200", description = "File returned")
    @ApiResponse(responseCode = "404", description = "Document not found for the given job/format")
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable("id") Long id,
            @RequestParam(value = "format", defaultValue = "pdf") String format) {
        return orchestratorService.download(id, format);
    }

    @Operation(summary = "List all saved versions of a BRD")
    @ApiResponse(responseCode = "200", description = "Version list returned")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<BrdVersionSummary>> listVersions(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orchestratorService.listVersions(id));
    }

    @Operation(summary = "Get the content of a specific BRD version")
    @ApiResponse(responseCode = "200", description = "Version content returned")
    @ApiResponse(responseCode = "404", description = "Job or version not found")
    @GetMapping("/{id}/versions/{versionNumber}")
    public ResponseEntity<BrdVersionContentResponse> getVersionContent(
            @PathVariable("id") Long id,
            @PathVariable("versionNumber") int versionNumber) {
        return ResponseEntity.ok(orchestratorService.getVersionContent(id, versionNumber));
    }

    @Operation(summary = "Save a manual edit as a new BRD version")
    @ApiResponse(responseCode = "200", description = "New version saved")
    @ApiResponse(responseCode = "400", description = "BRD not completed yet")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @PutMapping("/{id}/content")
    public ResponseEntity<BrdVersionSummary> saveContent(
            @PathVariable("id") Long id,
            @Valid @RequestBody BrdSaveEditRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal != null ? principal.getUsername() : "anonymous";
        return ResponseEntity.ok(orchestratorService.saveManualEdit(id, request.content(), userId));
    }

    @Operation(summary = "Refine a BRD with AI using a natural-language instruction")
    @ApiResponse(responseCode = "200", description = "Refined content returned as new version")
    @ApiResponse(responseCode = "400", description = "BRD not completed yet")
    @ApiResponse(responseCode = "502", description = "AI service error")
    @PostMapping("/{id}/refine")
    public ResponseEntity<BrdVersionContentResponse> refine(
            @PathVariable("id") Long id,
            @Valid @RequestBody BrdRefineRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal != null ? principal.getUsername() : "anonymous";
        return ResponseEntity.ok(orchestratorService.refineWithAi(id, request.prompt(), userId));
    }

    @Operation(summary = "Get code analysis results for a BRD job",
            description = "Returns the endpoints, relevant files, and notes discovered during analysis.")
    @ApiResponse(responseCode = "200", description = "Analysis data returned")
    @ApiResponse(responseCode = "404", description = "Job or analysis not found")
    @GetMapping("/{id}/analysis")
    public ResponseEntity<BrdAnalysisResponse> analysis(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orchestratorService.analysis(id));
    }

    @Operation(summary = "Push BRD as a story to JIRA Cloud")
    @ApiResponse(responseCode = "200", description = "Issue created")
    @ApiResponse(responseCode = "400", description = "BRD not completed yet")
    @ApiResponse(responseCode = "502", description = "JIRA API error")
    @PostMapping("/{id}/push/jira")
    public ResponseEntity<PushResponse> pushJira(
            @PathVariable("id") Long id,
            @Valid @RequestBody PushRequest request) {
        return ResponseEntity.ok(orchestratorService.pushToJira(id, request));
    }

    @Operation(summary = "Push BRD as an issue to Linear")
    @ApiResponse(responseCode = "200", description = "Issue created")
    @ApiResponse(responseCode = "400", description = "BRD not completed yet")
    @ApiResponse(responseCode = "502", description = "Linear API error")
    @PostMapping("/{id}/push/linear")
    public ResponseEntity<PushResponse> pushLinear(
            @PathVariable("id") Long id,
            @Valid @RequestBody PushRequest request) {
        return ResponseEntity.ok(orchestratorService.pushToLinear(id, request));
    }

    @Operation(summary = "Push BRD as a page to Confluence")
    @ApiResponse(responseCode = "200", description = "Page created")
    @ApiResponse(responseCode = "400", description = "BRD not completed yet")
    @ApiResponse(responseCode = "502", description = "Confluence API error")
    @PostMapping("/{id}/push/confluence")
    public ResponseEntity<PushResponse> pushConfluence(
            @PathVariable("id") Long id,
            @Valid @RequestBody ConfluencePushRequest request) {
        return ResponseEntity.ok(orchestratorService.pushToConfluence(id, request));
    }
}
