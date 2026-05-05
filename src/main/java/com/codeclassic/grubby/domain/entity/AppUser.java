package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "app_users",
        uniqueConstraints = @UniqueConstraint(name = "uq_users_username", columnNames = "username"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stores the user's email — used as the Spring Security principal. */
    @Column(nullable = false, length = 200)
    private String username;

    @Column(length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    @Column(length = 25)
    private String phone;

    /** BCrypt-hashed; never stored plaintext. */
    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Column(nullable = false, length = 200)
    @Builder.Default
    private String roles = "ROLE_USER";

    @Builder.Default
    private boolean enabled = true;

    private Instant createdAt;

    /** GitHub login name, populated after OAuth connection. */
    @Column(length = 100)
    private String githubLogin;

    /** AES-256-GCM encrypted GitHub access token. */
    @Column(length = 500)
    private String githubAccessToken;

    /** Optional Slack Incoming Webhook URL for BRD notifications. */
    @Column(length = 500)
    private String slackWebhookUrl;

    @PrePersist
    public void onCreate() {
        createdAt = Instant.now();
    }
}
