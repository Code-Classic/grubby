package com.codeclassic.grubby.service.security;

import com.codeclassic.grubby.repository.AllowedRepoHostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * P2 — SSRF protection via a DB-managed hostname allowlist.
 *
 * Validation steps (all must pass):
 *  1. URL parses cleanly
 *  2. Scheme is in the permitted set (https, http, git, ssh)
 *  3. Hostname is present and ≤ 253 chars
 *  4. Resolved IP is not in a private/loopback/link-local range
 *  5. Hostname exists in the allowed_repo_hosts table (enabled=true)
 *
 * Admins add rows to allowed_repo_hosts to permit new Git servers.
 * The table ships with no rows — the admin must seed it before any BRD
 * can be generated (see DataSeeder or the /api/v1/admin/allowed-hosts API).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http", "git", "ssh");

    private final AllowedRepoHostRepository allowedRepoHostRepository;

    /**
     * Validates the URL and throws ResponseStatusException(400) if any check fails.
     * Call this before passing repoUrl to GitIntegrationService.
     */
    public void validate(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoUrl must not be blank");
        }
        if (repoUrl.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl exceeds maximum length of 500 characters");
        }

        URI uri;
        try {
            uri = new URI(repoUrl.trim());
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl is not a valid URI: " + e.getReason());
        }

        // 1. Scheme check
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl scheme '" + scheme + "' is not permitted. Allowed: " + ALLOWED_SCHEMES);
        }

        // 2. Hostname presence
        String hostname = uri.getHost();
        if (hostname == null || hostname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl must contain a hostname");
        }
        hostname = hostname.toLowerCase();

        // 3. Private IP guard — resolve the hostname and check the resulting address
        try {
            InetAddress addr = InetAddress.getByName(hostname);
            if (isPrivateOrReserved(addr)) {
                log.warn("SSRF attempt blocked — repoUrl hostname '{}' resolved to private IP {}",
                        hostname, addr.getHostAddress());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "repoUrl resolves to a private or reserved IP address and is not permitted");
            }
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            // DNS failure or unknown host — reject rather than allow
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl hostname could not be resolved: " + hostname);
        }

        // 4. DB allowlist check
        boolean allowed = allowedRepoHostRepository
                .findByHostnameIgnoreCaseAndEnabledTrue(hostname)
                .isPresent();
        if (!allowed) {
            log.warn("Blocked repoUrl with hostname '{}' — not in allowlist", hostname);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Repository hostname '" + hostname + "' is not in the allowed list. " +
                    "Ask an admin to add it via the admin API.");
        }

        log.debug("repoUrl '{}' passed all SSRF checks", hostname);
    }

    /**
     * Returns true for IPs that must never be contacted by the application server:
     * loopback (127.x), link-local (169.254.x), site-local (10.x, 172.16-31.x, 192.168.x),
     * and the IPv6 equivalents.
     */
    private boolean isPrivateOrReserved(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress();
    }
}
