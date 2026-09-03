package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT j FROM AnalysisJob j WHERE j.jobId = :jobId")
    Optional<AnalysisJob> findByJobIdForUpdate(@Param("jobId") String jobId);

    boolean existsByJobId(String jobId);

    List<AnalysisJob> findAllByOrderByCreatedAtDesc();

    List<AnalysisJob> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Page<AnalysisJob> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    List<AnalysisJob> findByStatusInAndStartedAtBefore(
            List<AnalysisStatus> statuses,
            LocalDateTime threshold
    );

    // 기동 시 orphan 복구용: 이전 프로세스가 실행 중이던(RUNNING) 채로 죽어 남은 작업을 찾습니다.
    // monolith/단일 워커에서는 기동 직후 executor가 비어 있으므로 RUNNING 행은 전부 orphan입니다.
    List<AnalysisJob> findByStatusIn(List<AnalysisStatus> statuses);

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

    // 관리자 대시보드: 사용자별 전체 분석 작업 수(상태 무관)를 보여줄 때 사용합니다.
    long countByOwnerId(Long ownerId);

    long countByVideoAssetId(Long videoAssetId);

    boolean existsBySourceJobId(String sourceJobId);

    boolean existsByVideoAssetIdAndStatusNot(Long videoAssetId, AnalysisStatus status);

    boolean existsByVideoAssetIdAndCompletedAtAfter(Long videoAssetId, LocalDateTime completedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT j FROM AnalysisJob j WHERE j.videoAssetId = :videoAssetId ORDER BY j.id ASC")
    List<AnalysisJob> findAllByVideoAssetIdForUpdate(@Param("videoAssetId") Long videoAssetId);

    Optional<AnalysisJob> findByOwnerIdAndSourceJobIdAndAnalysisKindAndReanalysisIdempotencyKeyHash(
            Long ownerId,
            String sourceJobId,
            com.hanium.presentation.domain.analysis.type.AnalysisKind analysisKind,
            String reanalysisIdempotencyKeyHash
    );

    Optional<AnalysisJob> findFirstBySourceJobIdAndAnalysisKindAndStatusInOrderByCreatedAtDesc(
            String sourceJobId,
            com.hanium.presentation.domain.analysis.type.AnalysisKind analysisKind,
            List<AnalysisStatus> statuses
    );

    Optional<AnalysisJob> findFirstBySourceJobIdAndAnalysisKindOrderByCreatedAtDesc(
            String sourceJobId,
            com.hanium.presentation.domain.analysis.type.AnalysisKind analysisKind
    );

    // 관리자 사용자 목록 페이지용 배치 조회. 목록의 각 행마다 countByOwnerId를 따로
    // 호출하면 페이지 크기만큼(N+1) 쿼리가 나가므로, 페이지에 담긴 ownerId를 한 번에
    // 모아 집계합니다. 작업이 0건인 사용자는 GROUP BY 결과에 행 자체가 없으니 호출부에서
    // 기본값 0을 채워야 합니다.
    @Query("SELECT j.ownerId AS ownerId, COUNT(j) AS jobCount FROM AnalysisJob j WHERE j.ownerId IN :ownerIds GROUP BY j.ownerId")
    List<OwnerJobCount> countByOwnerIdIn(@Param("ownerIds") List<Long> ownerIds);

    interface OwnerJobCount {
        Long getOwnerId();

        Long getJobCount();
    }

    // 관리자 대시보드: 재시도 소진(DEAD_LETTER) 작업 목록을 최신 순으로 페이지네이션 조회합니다.
    Page<AnalysisJob> findByStatusOrderByCreatedAtDesc(AnalysisStatus status, Pageable pageable);

    // AI 코치가 "저번보다 나아졌나요?" 같은 질문에 답할 수 있도록, 현재 발표 외에 같은
    // 사용자의 다른 완료된 발표를 최근 순으로 가져옵니다. 재분석(VIDEO_LLM_REANALYSIS)은
    // 별도 발표가 아니라 같은 영상의 재처리이므로 STANDARD만 대상으로 합니다.
    List<AnalysisJob> findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
            Long ownerId,
            AnalysisStatus status,
            AnalysisKind analysisKind,
            String excludedJobId,
            Pageable pageable
    );
}
