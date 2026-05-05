package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.service.security.GitHubOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/github")
@RequiredArgsConstructor
@Tag(name = "GitHub OAuth", description = "Connect a GitHub account for repo access")
public class GitHubOAuthController {

    private final GitHubOAuthService oauthService;

    @Operation(summary = "Get GitHub OAuth authorization URL")
    @GetMapping("/authorize")
    public ResponseEntity<Map<String, String>> authorize(
            @AuthenticationPrincipal UserDetails principal) {
        URI url = oauthService.buildAuthorizationUrl(principal.getUsername());
        return ResponseEntity.ok(Map.of("url", url.toString()));
    }

    @Operation(summary = "GitHub OAuth callback (redirect from GitHub)")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state) {
        URI redirect = oauthService.handleCallback(code, state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect)
                .build();
    }

    @Operation(summary = "Disconnect the linked GitHub account")
    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal UserDetails principal) {
        oauthService.disconnect(principal.getUsername());
        return ResponseEntity.noContent().build();
    }
}
