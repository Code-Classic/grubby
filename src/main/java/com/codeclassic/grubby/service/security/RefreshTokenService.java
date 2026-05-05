package com.codeclassic.grubby.service.security;

import com.codeclassic.grubby.domain.entity.RefreshToken;
import com.codeclassic.grubby.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** 30 days */
    private static final long REFRESH_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private final RefreshTokenRepository repo;

    @Value("${jwt.secret}")
    private String jwtSecret; // used only as HMAC salt — not exposed

    // ── Issue ────────────────────────────────────────────────────────────────

    /**
     * Creates and persists a new refresh token for the given userId (email).
     * Returns the raw UUID token — this is the only time it is available in plain text.
     */
    @Transactional
    public String issue(String userId) {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        RefreshToken entity = RefreshToken.builder()
                .tokenHash(hash)
                .userId(userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusMillis(REFRESH_TTL_MS))
                .revoked(false)
                .build();
        repo.save(entity);
        log.debug("Issued refresh token for user '{}'", userId);
        return raw;
    }

    // ── Validate & Rotate ────────────────────────────────────────────────────

    /**
     * Validates the raw token, marks it revoked, and issues a fresh one.
     * Throws 401 if the token is missing, expired, or already revoked.
     *
     * @return the new raw refresh token
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken entity = repo.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid or expired refresh token"));

        if (entity.isRevoked()) {
            // Token reuse detected — revoke all tokens for this user (session theft mitigation)
            log.warn("Refresh token reuse detected for user '{}'. Revoking all sessions.", entity.getUserId());
            revokeAll(entity.getUserId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected");
        }

        if (Instant.now().isAfter(entity.getExpiresAt())) {
            entity.setRevoked(true);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        entity.setRevoked(true); // consume old token
        String newRaw = issue(entity.getUserId());
        return new RotationResult(entity.getUserId(), newRaw);
    }

    // ── Revoke ───────────────────────────────────────────────────────────────

    @Transactional
    public void revokeByRaw(String rawToken) {
        String hash = sha256(rawToken);
        repo.findByTokenHash(hash).ifPresent(t -> t.setRevoked(true));
    }

    @Transactional
    public void revokeAll(String userId) {
        repo.deleteByUserId(userId);
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * *") // 3 AM daily
    @Transactional
    public void purgeExpired() {
        repo.deleteExpiredOrRevoked(Instant.now());
        log.debug("Purged expired/revoked refresh tokens");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record RotationResult(String userId, String newRawToken) {}
}
