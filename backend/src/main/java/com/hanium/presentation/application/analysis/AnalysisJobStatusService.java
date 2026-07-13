package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 분석 파이프라인이 실행되는 "동안" AnalysisJob 상태를 즉시 DB에 커밋하기 위한 서비스입니다.
 *
 * <p>{@code AnalysisCommandService.executeAnalysisAsync()}는 업로드부터 결과 병합까지 전체
 * 파이프라인을 백그라운드 스레드에서 실행합니다. 이 메서드 자체는 하나의 큰 트랜잭션으로
 * 묶여 있지 않기 때문에(HTTP 요청이 이미 끝난 뒤에 실행되므로), 상태 변경마다 이 서비스의
 * 메서드를 호출해 그 자리에서 바로 커밋합니다.</p>
 *
 * <p>이 서비스의 메서드는 {@code REQUIRES_NEW}로 별도의 트랜잭션을 열어서 그 자리에서
 * 바로 커밋합니다. 그래서 Redis가 꺼져 있거나 응답이 늦어도, DB 상태 조회만으로도
 * 진행 상황이 실시간으로 반영됩니다.</p>
 */
@Service
public class AnalysisJobStatusService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobStatusService.class);

    private final AnalysisJobRepository analysisJobRepository;

    public AnalysisJobStatusService(AnalysisJobRepository analysisJobRepository) {
        this.analysisJobRepository = analysisJobRepository;
    }

    /**
     * QUEUED 상태인 작업을 "이 워커가 실행하겠다"고 선점(claim)합니다.
     *
     * <p>QUEUED일 때만 BASIC_ANALYZING으로 전이하고 true를 반환합니다. 이미 다른 워커가
     * 가져갔거나(=QUEUED가 아님) 작업이 없으면 false를 반환합니다. 재시작 복구로 같은 작업이
     * 두 번 투입되어도 한 워커만 실행하도록 보장합니다. 동시에 두 워커가 저장을 시도하면
     * {@code @Version} 낙관적 락으로 한쪽만 성공하고, 다른 쪽은 예외를 받아 false로 처리됩니다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimForExecution(String jobId) {
        return analysisJobRepository.findByJobId(jobId).map(analysisJob -> {
            if (!analysisJob.startExecutionIfQueued()) {
                return false;
            }
            try {
                analysisJobRepository.saveAndFlush(analysisJob);
            } catch (OptimisticLockingFailureException e) {
                // 다른 워커가 거의 동시에 먼저 선점했습니다. 이 워커는 실행하지 않습니다.
                log.info("[{}] 이미 다른 워커가 실행을 선점해 이 워커는 건너뜁니다.", jobId);
                return false;
            }
            log.info("[{}] 실행 선점 성공: QUEUED -> BASIC_ANALYZING", jobId);
            return true;
        }).orElseGet(() -> {
            log.warn("[{}] 실행 선점을 시도했지만 AnalysisJob을 찾지 못했습니다.", jobId);
            return false;
        });
    }

    /**
     * 워커 폴러가 "조회 후 실행 제출" 대신 "claim 후 실행 제출"할 수 있도록, QUEUED 후보를
     * 조회와 동시에 원자적으로 선점(BASIC_ANALYZING 전이)합니다.
     *
     * <p>여러 워커 인스턴스가 동시에 폴링해도, 행 잠금(PESSIMISTIC_WRITE)을 쥔 트랜잭션이
     * 커밋할 때까지 다른 인스턴스의 같은 조회는 대기하고, 커밋 후에는 이미 상태가 바뀐
     * 행이라 자연스럽게 결과에서 빠집니다. 그 결과 이 메서드가 반환한 작업은 호출 시점에
     * 이미 이 인스턴스가 실행 소유권을 확정한 상태이므로, 실행 제출 단계에서 별도로
     * {@link #claimForExecution(String)}을 다시 호출할 필요가 없습니다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AnalysisJob> claimNextQueuedJobs(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<AnalysisJob> candidates = analysisJobRepository.findByStatusOrderByCreatedAtAscForClaim(
                AnalysisStatus.QUEUED,
                PageRequest.of(0, limit)
        );

        List<AnalysisJob> claimed = new ArrayList<>();

        for (AnalysisJob candidate : candidates) {
            if (candidate.startExecutionIfQueued()) {
                claimed.add(candidate);
            }
        }

        analysisJobRepository.saveAll(claimed);

        if (!claimed.isEmpty()) {
            log.info("워커 폴러가 QUEUED 작업 {}건을 원자적으로 선점(claim)했습니다.", claimed.size());
        }

        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(String jobId, AnalysisStatus status) {
        analysisJobRepository.findByJobId(jobId).ifPresentOrElse(
                analysisJob -> {
                    applyStatus(analysisJob, status);
                    analysisJobRepository.save(analysisJob);
                    log.info("[{}] 상태 즉시 반영: {} ({})", jobId, status, status.getDescription());
                },
                () -> log.warn("[{}] 상태를 즉시 반영하려 했지만 AnalysisJob을 찾지 못했습니다.", jobId)
        );
    }

    // 분석 파이프라인은 이제 백그라운드 스레드에서 실행되고, 그 스레드에는 요청을
    // 감싸는 큰 트랜잭션이 없습니다. 그래서 완료/실패도 updateStatus와 동일하게
    // 그 자리에서 바로 커밋하는 메서드가 필요합니다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStatus(String jobId) {
        analysisJobRepository.findByJobId(jobId).ifPresentOrElse(
                analysisJob -> {
                    analysisJob.complete();
                    analysisJobRepository.save(analysisJob);
                    log.info("[{}] 상태 즉시 반영: COMPLETED", jobId);
                },
                () -> log.warn("[{}] 상태를 즉시 반영하려 했지만 AnalysisJob을 찾지 못했습니다.", jobId)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStatus(String jobId, String failReason) {
        analysisJobRepository.findByJobId(jobId).ifPresentOrElse(
                analysisJob -> {
                    analysisJob.fail(failReason);
                    analysisJobRepository.save(analysisJob);
                    log.info("[{}] 상태 즉시 반영: FAILED ({})", jobId, failReason);
                },
                () -> log.warn("[{}] 상태를 즉시 반영하려 했지만 AnalysisJob을 찾지 못했습니다.", jobId)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCancel(String jobId) {
        analysisJobRepository.findByJobId(jobId).ifPresentOrElse(
                analysisJob -> {
                    if (!analysisJob.requestCancel()) {
                        log.info(
                                "[{}] 취소 요청을 반영하지 않았습니다. status={}",
                                jobId,
                                analysisJob.getStatus()
                        );
                        return;
                    }

                    analysisJobRepository.save(analysisJob);
                    log.info("[{}] 취소 요청 즉시 반영", jobId);
                },
                () -> log.warn("[{}] 취소 요청을 반영하려 했지만 AnalysisJob을 찾지 못했습니다.", jobId)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelStatus(String jobId) {
        analysisJobRepository.findByJobId(jobId).ifPresentOrElse(
                analysisJob -> {
                    analysisJob.markCancelled();
                    analysisJobRepository.save(analysisJob);
                    log.info("[{}] 상태 즉시 반영: CANCELLED", jobId);
                },
                () -> log.warn("[{}] 상태를 즉시 반영하려 했지만 AnalysisJob을 찾지 못했습니다.", jobId)
        );
    }

    private void applyStatus(AnalysisJob analysisJob, AnalysisStatus status) {
        switch (status) {
            case BASIC_ANALYZING -> analysisJob.startBasicAnalysis();
            case VIDEO_LLM_ANALYZING -> analysisJob.startVideoLlmAnalysis();
            case COMPACTING -> analysisJob.startCompacting();
            case OPENAI_GENERATING -> analysisJob.startOpenAiGenerating();
            case MERGING_RESULT -> analysisJob.startMergingResult();
            default -> log.debug(
                    "AnalysisJobStatusService에서 별도로 처리하지 않는 상태입니다: {}",
                    status
            );
        }
    }
}
