package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByJobId(String jobId);

    Optional<AnalysisJob> findByJobIdAndOwnerId(String jobId, Long ownerId);

    boolean existsByJobId(String jobId);

    List<AnalysisJob> findAllByOrderByCreatedAtDesc();

    List<AnalysisJob> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
}
