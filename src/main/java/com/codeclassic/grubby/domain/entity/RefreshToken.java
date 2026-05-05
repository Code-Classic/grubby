package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens",
        indexes = @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String tokenHash; // SHA-256 hex of the raw token

    @Column(nullable = false, length = 200)
    private String userId; // email used as principal

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    @PrePersist
    void prePersist() {
        if (issuedAt == null) issuedAt = Instant.now();
    }
}
