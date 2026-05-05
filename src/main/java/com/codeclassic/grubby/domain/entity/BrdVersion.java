package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "brd_versions", indexes = {
        @Index(name = "idx_brd_versions_request_id", columnList = "requestId")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrdVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private int versionNumber;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeType changeType;

    @Column(length = 1000)
    private String changePrompt;

    @Column(length = 255)
    private String createdBy;

    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
