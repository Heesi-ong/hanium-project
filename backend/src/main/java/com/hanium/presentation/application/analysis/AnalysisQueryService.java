package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisQueryService {

    private final AnalysisJobRepository analysisJobRepository;

    public AnalysisQueryService(AnalysisJobRepository analysisJobRepository) {
        this.analysisJobRepository = analysisJobRepository;
    }

    @Transactional(readOnly = true)
    public AnalysisStatusResponse getStatus(String jobId, Long ownerId) {
        AnalysisJob analysisJob = getAnalysisJob(jobId);

        validateOwnership(analysisJob, ownerId);

        return AnalysisStatusResponse.from(analysisJob);
    }

    private AnalysisJob getAnalysisJob(String jobId) {
        return analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));
    }

    private void validateOwnership(AnalysisJob analysisJob, Long ownerId) {
        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }
    }
}
