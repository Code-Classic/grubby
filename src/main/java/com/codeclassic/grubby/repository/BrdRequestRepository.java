package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BrdRequestRepository
        extends JpaRepository<BrdRequest, Long>,
                JpaSpecificationExecutor<BrdRequest> {

    long countByUserId(String userId);
    long countByUserIdAndStatus(String userId, BrdStatus status);
}
