package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.ChangelogJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangelogJobRepository extends JpaRepository<ChangelogJob, Long> {
    Page<ChangelogJob> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
