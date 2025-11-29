package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "analysis_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long requestId;

    private String repoHash; // repoUrl + commitSha canonical hash

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String endpointsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String modelsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String servicesJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String graphJson;

    private Instant analyzedAt;

    @PrePersist
    public void onCreate() {
        analyzedAt = Instant.now();
    }
}