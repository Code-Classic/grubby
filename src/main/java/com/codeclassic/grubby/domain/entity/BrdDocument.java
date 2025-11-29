package com.codeclassic.grubby.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "brd_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrdDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long requestId;
    private String format; // pdf|docx|md
    private String storageKey;
    private long sizeBytes;
    private String checksum;
    private Instant generatedAt;

    @PrePersist
    public void onCreate() {
        if (generatedAt == null) generatedAt = Instant.now();
    }
}
