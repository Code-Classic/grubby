package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "brd_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrdRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private String repoUrl;
    private String branch;
    private String commitSha;
    private String projectType;

    @Lob
    private String featureContext;

    @Enumerated(EnumType.STRING)
    private BrdStatus status;

    private String stage;
    private Integer progressPct;

    @Lob
    private String errorMessage;

    private String authType;
    private String tokenRef; // reference to secure store (do not store token directly)

    private boolean cachedResult;

    private boolean forceReanalyze;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (progressPct == null) progressPct = 0;
        if (status == null) status = BrdStatus.QUEUED;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}