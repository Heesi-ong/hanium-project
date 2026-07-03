package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
