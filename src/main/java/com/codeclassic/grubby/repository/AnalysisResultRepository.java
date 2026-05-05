package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    Optional<AnalysisResult> findTopByRequestIdOrderByIdDesc(Long requestId);
}
