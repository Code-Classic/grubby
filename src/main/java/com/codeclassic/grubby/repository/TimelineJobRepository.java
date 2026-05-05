package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.TimelineJob;
import com.codeclassic.grubby.domain.entity.TimelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineJobRepository extends JpaRepository<TimelineJob, Long> {

    Page<TimelineJob> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserId(String userId);

    long countByUserIdAndStatus(String userId, TimelineStatus status);
}
