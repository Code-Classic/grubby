package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * P2 — DB-managed SSRF allowlist.
 * Each row permits one hostname pattern (exact match, e.g. "github.com").
 * Admins manage this table directly in the DB or via the admin API.
 *
 * Examples of valid entries:
 *   github.com, gitlab.com, bitbucket.org, your-internal-git.corp
 *
 * The validator resolves the submitted repoUrl's hostname and checks it
 * against this table before allowing a clone.
 */
@Entity
@Table(name = "allowed_repo_hosts",
        uniqueConstraints = @UniqueConstraint(name = "uq_allowed_host", columnNames = "hostname"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AllowedRepoHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Exact lowercase hostname, e.g. "github.com".
     * No wildcards — add each subdomain explicitly if needed.
     */
    @Column(nullable = false, length = 253)
    private String hostname;

    /** Human-readable note, e.g. "GitHub public" */
    @Column(length = 500)
    private String description;

    @Builder.Default
    private boolean enabled = true;

    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        createdAt = Instant.now();
    }
}
