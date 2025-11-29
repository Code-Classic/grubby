package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {
}
