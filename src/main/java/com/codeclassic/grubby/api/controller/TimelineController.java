package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.GenerateTimelineRequest;
import com.codeclassic.grubby.api.dto.TimelineStatusResponse;
import com.codeclassic.grubby.api.dto.TimelineSubmitResponse;
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
    public ResponseEntity<TimelineSubmitResponse> generate(
            @Valid @RequestBody GenerateTimelineRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal.getUsername();
        Long id = orchestrator.submit(req, userId);
        return ResponseEntity.accepted()
                .body(new TimelineSubmitResponse(id.toString(), "QUEUED",
                        "Timeline job queued — poll /status for progress"));
    }

    @Operation(summary = "Get status of a timeline job")
    @GetMapping("/{id}/status")
    public ResponseEntity<TimelineStatusResponse> status(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.status(id));
    }

    @Operation(summary = "Get the generated timeline document in Markdown")
    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> preview(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.preview(id));
    }

    @Operation(summary = "Download the timeline as a Markdown file")
    @GetMapping("/{id}/download")
    public ResponseEntity<String> download(@PathVariable Long id) {
        String content = orchestrator.preview(id);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=timeline-" + id + ".md")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(content);
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
                        "id",           j.getId().toString(),
                        "repoUrl",      j.getRepoUrl(),
                        "branch",       j.getBranch() != null ? j.getBranch() : "",
                        "status",       j.getStatus().name(),
                        "progressPct",  j.getProgressPct(),
                        "totalCommits", j.getTotalCommits() != null ? j.getTotalCommits() : 0,
                        "analyzedCommits", j.getAnalyzedCommits() != null ? j.getAnalyzedCommits() : 0,
                        "createdAt",    j.getCreatedAt() != null ? j.getCreatedAt().toString() : Instant.now().toString()
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
