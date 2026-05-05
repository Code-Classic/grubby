package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "changelog_jobs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ChangelogJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String userId;

    @Column(nullable = false, length = 500)
    private String repoUrl;

    @Column(length = 200)
    private String branch;

    @Column(length = 500)
    private String authToken;

    /** Starting ref: tag name, branch name, or full commit SHA. */
    @Column(nullable = false, length = 200)
    private String fromRef;

    /** Ending ref: tag name, branch name, or commit SHA. Defaults to HEAD when null. */
    @Column(length = 200)
    private String toRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChangelogStatus status;

    @Column(length = 50)
    private String stage;

    @Column(nullable = false)
    @Builder.Default
    private int progressPct = 0;

    @Lob
    private String errorMessage;

    private Integer commitCount;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String markdownContent;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = ChangelogStatus.QUEUED;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
