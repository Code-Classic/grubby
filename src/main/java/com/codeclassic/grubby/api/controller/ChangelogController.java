package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.ChangelogStatusResponse;
import com.codeclassic.grubby.api.dto.GenerateChangelogRequest;
import com.codeclassic.grubby.domain.entity.ChangelogJob;
import com.codeclassic.grubby.service.orchestrator.ChangelogOrchestratorService;
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
@RequestMapping("/api/v1/changelog")
@RequiredArgsConstructor
@Tag(name = "Changelog", description = "AI changelog generation between git refs")
public class ChangelogController {

    private final ChangelogOrchestratorService orchestrator;

    @Operation(summary = "Submit a changelog generation job")
    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generate(
            @Valid @RequestBody GenerateChangelogRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long id = orchestrator.submit(req, principal.getUsername());
        return ResponseEntity.accepted().body(Map.of(
                "id", id.toString(),
                "status", "QUEUED",
                "message", "Changelog job queued — poll /status for progress"));
    }

    @Operation(summary = "Get status of a changelog job")
    @GetMapping("/{id}/status")
    public ResponseEntity<ChangelogStatusResponse> status(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.status(id));
    }

    @Operation(summary = "Get the generated changelog in Markdown")
    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> preview(@PathVariable Long id) {
        return ResponseEntity.ok(orchestrator.preview(id));
    }

    @Operation(summary = "Download the changelog as a Markdown file")
    @GetMapping("/{id}/download")
    public ResponseEntity<String> download(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=CHANGELOG-" + id + ".md")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(orchestrator.preview(id));
    }

    @Operation(summary = "List changelog jobs for the current user")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetails principal) {
        Page<ChangelogJob> result = orchestrator.list(principal.getUsername(), page, size);
        List<Map<String, Object>> items = result.getContent().stream()
                .map(j -> Map.<String, Object>of(
                        "id",          j.getId().toString(),
                        "repoUrl",     j.getRepoUrl(),
                        "fromRef",     j.getFromRef(),
                        "toRef",       j.getToRef() != null ? j.getToRef() : "HEAD",
                        "status",      j.getStatus().name(),
                        "progressPct", j.getProgressPct(),
                        "commitCount", j.getCommitCount() != null ? j.getCommitCount() : 0,
                        "createdAt",   j.getCreatedAt() != null ? j.getCreatedAt().toString() : Instant.now().toString()
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
