package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.AllowedHostRequest;
import com.codeclassic.grubby.domain.entity.AllowedRepoHost;
import com.codeclassic.grubby.repository.AllowedRepoHostRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * P2 — Admin-only API for managing the repo hostname allowlist.
 * All endpoints require ROLE_ADMIN (enforced by SecurityConfig + @PreAuthorize).
 *
 * GET    /api/v1/admin/allowed-hosts          — list all active hosts
 * POST   /api/v1/admin/allowed-hosts          — add a new host
 * DELETE /api/v1/admin/allowed-hosts/{id}     — soft-disable a host
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/allowed-hosts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Allowlist management (ROLE_ADMIN only)")
public class AdminController {

    private final AllowedRepoHostRepository allowedRepoHostRepository;

    @Operation(summary = "List all enabled allowed repo hostnames")
    @GetMapping
    public ResponseEntity<List<AllowedRepoHost>> list() {
        return ResponseEntity.ok(allowedRepoHostRepository.findAllByEnabledTrue());
    }

    @Operation(summary = "Add a hostname to the allowlist")
    @PostMapping
    public ResponseEntity<AllowedRepoHost> add(@Valid @RequestBody AllowedHostRequest req) {
        String hostname = req.getHostname().toLowerCase().trim();
        allowedRepoHostRepository.findByHostnameIgnoreCaseAndEnabledTrue(hostname)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Hostname already in allowlist: " + hostname);
                });
        AllowedRepoHost host = AllowedRepoHost.builder()
                .hostname(hostname)
                .description(req.getDescription())
                .enabled(true)
                .build();
        AllowedRepoHost saved = allowedRepoHostRepository.save(host);
        log.info("Admin added hostname '{}' to allowlist", hostname);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Disable (soft-delete) a hostname from the allowlist")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> disable(@PathVariable Long id) {
        AllowedRepoHost host = allowedRepoHostRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Allowed host not found: " + id));
        host.setEnabled(false);
        allowedRepoHostRepository.save(host);
        log.info("Admin disabled hostname '{}' (id={})", host.getHostname(), id);
        return ResponseEntity.ok(Map.of("message", "Hostname disabled: " + host.getHostname()));
    }
}
