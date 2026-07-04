package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByJobId(String jobId);

    Optional<AnalysisJob> findByJobIdAndOwnerId(String jobId, Long ownerId);

    boolean existsByJobId(String jobId);

    List<AnalysisJob> findAllByOrderByCreatedAtDesc();

    List<AnalysisJob> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Page<AnalysisJob> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    List<AnalysisJob> findByStatusInAndStartedAtBefore(
            List<AnalysisStatus> statuses,
            LocalDateTime threshold
    );

    List<AnalysisJob> findByStatusAndCompletedAtBefore(
            AnalysisStatus status,
            LocalDateTime threshold
    );
}
