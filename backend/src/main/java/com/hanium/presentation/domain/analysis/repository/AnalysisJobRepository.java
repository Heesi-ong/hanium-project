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

    // 재시작 등으로 워커에 투입되지 못한 채 오래 QUEUED로 남아 있는 작업을 찾습니다.
    List<AnalysisJob> findByStatusAndStartedAtBefore(
            AnalysisStatus status,
            LocalDateTime threshold
    );

    // 워커 폴러가 접수 순서(FIFO)대로 QUEUED 작업을 가져갈 때 사용합니다. Pageable로 개수를 제한합니다.
    List<AnalysisJob> findByStatusOrderByCreatedAtAsc(
            AnalysisStatus status,
            Pageable pageable
    );
}
