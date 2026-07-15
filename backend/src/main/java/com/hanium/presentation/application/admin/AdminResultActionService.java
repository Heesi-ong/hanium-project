package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminResultActionService {

    private final AnalysisJobRepository analysisJobRepository;
    private final ResultCommandService resultCommandService;
    private final AdminAuditLogService adminAuditLogService;

    public AdminResultActionService(
            AnalysisJobRepository analysisJobRepository,
            ResultCommandService resultCommandService,
            AdminAuditLogService adminAuditLogService
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.resultCommandService = resultCommandService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional
    public void deleteResult(Long adminId, String adminEmail, String jobId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        resultCommandService.deleteResult(jobId, analysisJob.getOwnerId());

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.DELETE_RESULT,
                AdminAuditTargetType.ANALYSIS_JOB,
                jobId,
                null
        );
    }
}
