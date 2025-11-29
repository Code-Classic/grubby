package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.*;
import com.codeclassic.grubby.service.orchestrator.BrdOrchestratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/brd")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BrdController {

    private final BrdOrchestratorService orchestratorService;

    @PostMapping("/generate")
    public ResponseEntity<GenerateBrdResponse> generate(@Valid @RequestBody GenerateBrdRequest request,
                                                        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        log.info("Generate BRD request received from user {}: {}", userId, request);
        Long id = orchestratorService.submit(request, userId);
        return ResponseEntity.accepted().body(new GenerateBrdResponse(id.toString(), "QUEUED", "Job accepted"));
    }

    @GetMapping("/{id}/status")
    public BrdStatusResponse status(@PathVariable("id") Long id) {
        return orchestratorService.status(id);
    }

    @GetMapping("/{id}/preview")
    public BrdPreviewResponse preview(@PathVariable("id") Long id,
                                      @RequestParam(value = "format", defaultValue = "markdown") String format) {
        return orchestratorService.preview(id, format);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id,
                                             @RequestParam(value = "format", defaultValue = "pdf") String format) {
        return orchestratorService.download(id, format);
    }
}
