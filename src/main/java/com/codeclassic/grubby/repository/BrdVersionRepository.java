package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.BrdVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrdVersionRepository extends JpaRepository<BrdVersion, Long> {
    List<BrdVersion> findByRequestIdOrderByVersionNumberDesc(Long requestId);
    Optional<BrdVersion> findFirstByRequestIdOrderByVersionNumberDesc(Long requestId);
    Optional<BrdVersion> findByRequestIdAndVersionNumber(Long requestId, int versionNumber);
    long countByRequestId(Long requestId);
}
