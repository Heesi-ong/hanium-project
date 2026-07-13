package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

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

    // 워커 폴러가 접수 순서(FIFO)대로 QUEUED 후보를 원자적으로 선점(claim)할 때 사용합니다.
    // PESSIMISTIC_WRITE(SELECT ... FOR UPDATE)로 행 잠금을 걸어, 여러 워커 인스턴스가 동시에
    // 폴링해도 같은 후보를 반복해서 골라 실행 제출까지 갔다가 뒤늦게 선점 실패를 발견하는
    // 낭비를 없앱니다. 잠금을 쥔 트랜잭션이 커밋하기 전까지 다른 트랜잭션의 같은 조회는
    // 대기하고, 커밋 후에는 이미 상태가 바뀐 행이라 자연스럽게 결과에서 제외됩니다.
    //
    // MySQL의 FOR UPDATE SKIP LOCKED도 검토했지만, H2(로컬/테스트)와 MySQL(dev/prod) 양쪽에서
    // 동일하게 동작하는 이식성을 우선해 일반 FOR UPDATE를 선택했습니다. 후보를 나눠 갖기 위해
    // 짧게 대기하는 비용은 폴링 주기(기본 1초)에 비해 무시할 만한 트레이드오프입니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT j FROM AnalysisJob j WHERE j.status = :status ORDER BY j.createdAt ASC")
    List<AnalysisJob> findByStatusOrderByCreatedAtAscForClaim(
            @Param("status") AnalysisStatus status,
            Pageable pageable
    );

    // 관측용: 큐 적체(대기)·실행 중 작업 수를 게이지 메트릭으로 노출할 때 사용합니다.
    long countByStatus(AnalysisStatus status);

    long countByStatusIn(List<AnalysisStatus> statuses);

    // 큐 백프레셔 검사용: 사용자별 QUEUED 작업 수. 전역 한도와 별도로, 한 사용자가
    // 대기열을 독점하지 못하게 막을 때 사용합니다.
    long countByStatusAndOwnerId(AnalysisStatus status, Long ownerId);
}
