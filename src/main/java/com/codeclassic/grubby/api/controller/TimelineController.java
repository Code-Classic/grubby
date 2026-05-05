package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.GenerateTimelineRequest;
import com.codeclassic.grubby.api.dto.ShareTimelineResponse;
import com.codeclassic.grubby.api.dto.TimelineStatusResponse;
import com.codeclassic.grubby.domain.entity.TimelineJob;
import com.codeclassic.grubby.service.orchestrator.TimelineOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/timeline")
@RequiredArgsConstructor
@Tag(name = "Timeline", description = "Development timeline generation from git history")
public class TimelineController {

    private final TimelineOrchestratorService orchestrator;

    @Operation(summary = "Submit a repository for timeline generation")
    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(
            @Valid @RequestBody GenerateTimelineRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long id = orchestrator.submit(req, principal.getUsername());
        return ResponseEntity.accepted().body(Map.of(
                "id", id.toString(),
                "status", "QUEUED",
                "message", "Timeline job queued — poll /status for progress"));
    }

    @Operation(summary = "Get status of a timeline job")
    @GetMapping("/{id}/status")
    public ResponseEntity<TimelineStatusResponse> status(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.status(id));
    }

    @Operation(summary = "Get the generated timeline in Markdown")
    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> preview(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.preview(id));
    }

    @Operation(summary = "Download the timeline as a Markdown file")
    @GetMapping("/{id}/download")
    public ResponseEntity<String> download(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=timeline-" + id + ".md")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(orchestrator.preview(id));
    }

    @Operation(summary = "Get structured commit data for the interactive visual timeline")
    @GetMapping(value = "/{id}/visual", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> visual(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.visualData(id));
    }

    @Operation(summary = "Get per-author contribution statistics")
    @GetMapping(value = "/{id}/contribution", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> contribution(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.contributionData(id));
    }

    @Operation(summary = "Get detected architecture evolution signals")
    @GetMapping(value = "/{id}/architecture", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> architecture(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.architectureData(id));
    }

    @Operation(summary = "Generate a public share link for a completed timeline")
    @PostMapping("/{id}/share")
    public ResponseEntity<ShareTimelineResponse> share(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(orchestrator.share(id, principal.getUsername()));
    }

    @Operation(summary = "Get a publicly shared timeline by its share token (no auth required)")
    @GetMapping("/public/{token}")
    public ResponseEntity<Map<String, Object>> publicTimeline(@PathVariable String token) {
        TimelineJob job = orchestrator.getByShareToken(token);
        return ResponseEntity.ok(Map.of(
                "id",               job.getId().toString(),
                "repoUrl",          job.getRepoUrl(),
                "branch",           job.getBranch() != null ? job.getBranch() : "",
                "totalCommits",     job.getTotalCommits() != null ? job.getTotalCommits() : 0,
                "analyzedCommits",  job.getAnalyzedCommits() != null ? job.getAnalyzedCommits() : 0,
                "markdownContent",  job.getMarkdownContent() != null ? job.getMarkdownContent() : "",
                "commitDataJson",   job.getCommitDataJson() != null ? job.getCommitDataJson() : "[]",
                "contributionJson", job.getContributionJson() != null ? job.getContributionJson() : "{}",
                "architectureJson", job.getArchitectureJson() != null ? job.getArchitectureJson() : "[]",
                "createdAt",        job.getCreatedAt() != null ? job.getCreatedAt().toString() : Instant.now().toString()
        ));
    }

    @Operation(summary = "List timeline jobs for the current user")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetails principal) {
        Page<TimelineJob> result = orchestrator.list(principal.getUsername(), page, size);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(j -> Map.<String, Object>of(
                        "id",              j.getId().toString(),
                        "repoUrl",         j.getRepoUrl(),
                        "branch",          j.getBranch() != null ? j.getBranch() : "",
                        "status",          j.getStatus().name(),
                        "progressPct",     j.getProgressPct(),
                        "totalCommits",    j.getTotalCommits() != null ? j.getTotalCommits() : 0,
                        "analyzedCommits", j.getAnalyzedCommits() != null ? j.getAnalyzedCommits() : 0,
                        "createdAt",       j.getCreatedAt() != null ? j.getCreatedAt().toString() : Instant.now().toString()
                ))
                .toList();
        return ResponseEntity.ok(Map.of(
                "content",       items,
                "page",          result.getNumber(),
                "totalElements", result.getTotalElements(),
                "totalPages",    result.getTotalPages(),
                "last",          result.isLast()
        ));
    }
}
