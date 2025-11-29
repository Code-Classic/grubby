package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.BrdDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrdDocumentRepository extends JpaRepository<BrdDocument, Long> {
    Optional<BrdDocument> findFirstByRequestIdAndFormatOrderByGeneratedAtDesc(Long requestId, String format);
}
