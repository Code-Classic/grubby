package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "timeline_jobs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TimelineJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String userId;

    @Column(nullable = false, length = 500)
    private String repoUrl;

    @Column(length = 200)
    private String branch;

    /** Raw auth token held only for the duration of the job — never logged. */
    @Column(length = 500)
    private String authToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TimelineStatus status;

    @Column(length = 50)
    private String stage;

    @Column(nullable = false)
    @Builder.Default
    private int progressPct = 0;

    @Lob
    private String errorMessage;

    /** Total commits found in the repo (before filtering). */
    private Integer totalCommits;

    /** Number of commits sent to the AI after smart filtering. */
    private Integer analyzedCommits;

    /** Generated markdown content — stored directly for simplicity. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String markdownContent;

    /** UUID share token — set when the user shares this timeline publicly. */
    @Column(unique = true, length = 36)
    private String shareToken;

    @Column(nullable = false)
    @Builder.Default
    private boolean isPublic = false;

    /** JSON array of CommitSnapshot — powers the interactive visual timeline. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String commitDataJson;

    /** JSON-serialised ContributionSummary — per-author stats and knowledge silos. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String contributionJson;

    /** JSON array of ArchitectureSignal — detected architectural inflection points. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String architectureJson;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = TimelineStatus.QUEUED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
