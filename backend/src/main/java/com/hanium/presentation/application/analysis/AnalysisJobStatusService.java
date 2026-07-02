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
 * <p>{@code AnalysisCommandService.executeAnalysis()}는 업로드부터 결과 병합까지 전체
 * 파이프라인을 하나의 {@code @Transactional} 메서드 안에서 실행합니다. 그 메서드 안에서
 * {@code analysisJob.startBasicAnalysis()} 같은 상태 변경은 메서드가 끝나고 트랜잭션이
 * 커밋될 때 한 번에 DB에 반영되기 때문에, 분석이 진행되는 도중에 다른 요청(예: 진행률
 * 조회 API)이 DB를 조회해도 중간 상태를 볼 수 없고 처음(UPLOADED) 상태만 계속 보이게
 * 됩니다.</p>
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
