package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.analysis.AnalysisCommandService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnalysisJobActionService {

    private final AnalysisCommandService analysisCommandService;
    private final AdminAuditLogService adminAuditLogService;

    public AdminAnalysisJobActionService(
            AnalysisCommandService analysisCommandService,
            AdminAuditLogService adminAuditLogService
    ) {
        this.analysisCommandService = analysisCommandService;
        this.adminAuditLogService = adminAuditLogService;
    }

    @Transactional
    public void requeueDeadLetterJob(Long adminId, String adminEmail, String jobId) {
        analysisCommandService.requeueDeadLetterJob(jobId);

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.REQUEUE_DEAD_LETTER_JOB,
                AdminAuditTargetType.ANALYSIS_JOB,
                jobId,
                null
        );
    }
}
