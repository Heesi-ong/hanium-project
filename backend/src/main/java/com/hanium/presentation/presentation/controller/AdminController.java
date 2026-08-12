package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.admin.AdminAnalysisJobActionService;
import com.hanium.presentation.application.admin.AdminAuditLogService;
import com.hanium.presentation.application.admin.AdminDashboardService;
import com.hanium.presentation.application.admin.AdminResultActionService;
import com.hanium.presentation.application.admin.AdminStorageDeletionTaskActionService;
import com.hanium.presentation.application.admin.AdminPasswordResetEmailTaskActionService;
import com.hanium.presentation.application.admin.AdminUserActionService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.domain.user.type.UserStatus;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.logging.RequestIdFilter;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.request.AdminActionReasonRequest;
import com.hanium.presentation.presentation.dto.response.AdminAnalysisJobSummaryResponse;
import com.hanium.presentation.presentation.dto.response.AdminAuditLogResponse;
import com.hanium.presentation.presentation.dto.response.AdminStatsResponse;
import com.hanium.presentation.presentation.dto.response.AdminStorageDeletionTaskResponse;
import com.hanium.presentation.presentation.dto.response.AdminPasswordResetEmailTaskResponse;
import com.hanium.presentation.presentation.dto.response.AdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminAnalysisJobSummaryResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminAuditLogResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminStorageDeletionTaskResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminPasswordResetEmailTaskResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.PagedResultSummaryResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 관리자 전용 API입니다. 조회(사용자 목록/집계 통계/사용자별 분석 결과 목록/감사로그)에 더해
 * 계정 정지/활성화, 강제 탈퇴, 분석 결과 삭제 액션을 제공합니다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminDashboardService adminDashboardService;
    private final AdminAuditLogService adminAuditLogService;
    private final AdminUserActionService adminUserActionService;
    private final AdminResultActionService adminResultActionService;
    private final AdminAnalysisJobActionService adminAnalysisJobActionService;
    private final AdminStorageDeletionTaskActionService adminStorageDeletionTaskActionService;
    private final AdminPasswordResetEmailTaskActionService adminPasswordResetEmailTaskActionService;

    public AdminController(
            AdminDashboardService adminDashboardService,
            AdminAuditLogService adminAuditLogService,
            AdminUserActionService adminUserActionService,
            AdminResultActionService adminResultActionService,
            AdminAnalysisJobActionService adminAnalysisJobActionService,
            AdminStorageDeletionTaskActionService adminStorageDeletionTaskActionService,
            AdminPasswordResetEmailTaskActionService adminPasswordResetEmailTaskActionService
    ) {
        this.adminDashboardService = adminDashboardService;
        this.adminAuditLogService = adminAuditLogService;
        this.adminUserActionService = adminUserActionService;
        this.adminResultActionService = adminResultActionService;
        this.adminAnalysisJobActionService = adminAnalysisJobActionService;
        this.adminStorageDeletionTaskActionService = adminStorageDeletionTaskActionService;
        this.adminPasswordResetEmailTaskActionService = adminPasswordResetEmailTaskActionService;
    }

    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.success("관리자 권한이 확인되었습니다.", "pong");
    }

    @GetMapping("/users")
    public ApiResponse<PagedAdminUserSummaryResponse> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) UserRole role
    ) {
        Page<AdminUserSummaryResponse> users = adminDashboardService.getUsers(
                email,
                status,
                role,
                createPageable(page, size)
        );

        return ApiResponse.success(
                "사용자 목록 조회가 완료되었습니다.",
                PagedAdminUserSummaryResponse.from(users)
        );
    }

    @GetMapping("/stats")
    public ApiResponse<AdminStatsResponse> getStats() {
        return ApiResponse.success(
                "집계 통계 조회가 완료되었습니다.",
                adminDashboardService.getStats()
        );
    }

    @GetMapping("/users/{userId}/results")
    public ApiResponse<PagedResultSummaryResponse> getUserResults(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<ResultSummaryResponse> results = adminDashboardService.getUserResults(
                userId,
                createPageable(page, size)
        );

        return ApiResponse.success(
                "사용자 분석 결과 목록 조회가 완료되었습니다.",
                PagedResultSummaryResponse.from(results)
        );
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PagedAdminAuditLogResponse> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String adminEmail,
            @RequestParam(required = false) AdminAuditAction action,
            @RequestParam(required = false) AdminAuditTargetType targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "감사로그 조회 시작 시각은 종료 시각보다 늦을 수 없습니다."
            );
        }

        Page<AdminAuditLogResponse> auditLogs = adminAuditLogService.getAuditLogs(
                adminEmail,
                action,
                targetType,
                targetId,
                from,
                to,
                createPageable(page, size)
        );

        return ApiResponse.success(
                "관리자 감사로그 조회가 완료되었습니다.",
                PagedAdminAuditLogResponse.from(auditLogs)
        );
    }

    @PostMapping("/users/{userId}/suspend")
    public ApiResponse<Void> suspendUser(
            @PathVariable Long userId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminUserActionService.suspendUser(
                getCurrentUserId(authentication),
                authentication.getName(),
                userId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );

        return ApiResponse.success("사용자를 정지했습니다.");
    }

    @PostMapping("/users/{userId}/activate")
    public ApiResponse<Void> activateUser(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        adminUserActionService.activateUser(getCurrentUserId(authentication), authentication.getName(), userId);

        return ApiResponse.success("사용자를 활성화했습니다.");
    }

    @PostMapping("/users/{userId}/withdraw")
    public ApiResponse<Void> forceWithdrawUser(
            @PathVariable Long userId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminUserActionService.forceWithdrawUser(
                getCurrentUserId(authentication),
                authentication.getName(),
                userId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );

        return ApiResponse.success("사용자를 강제 탈퇴시켰습니다.");
    }

    @GetMapping("/analysis-jobs/dead-letter")
    public ApiResponse<PagedAdminAnalysisJobSummaryResponse> getDeadLetterJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AdminAnalysisJobSummaryResponse> jobs = adminDashboardService.getDeadLetterJobs(
                createPageable(page, size)
        );

        return ApiResponse.success(
                "재시도 소진 작업 목록 조회가 완료되었습니다.",
                PagedAdminAnalysisJobSummaryResponse.from(jobs)
        );
    }

    @PostMapping("/analysis-jobs/{jobId}/requeue")
    public ApiResponse<Void> requeueDeadLetterJob(
            @PathVariable String jobId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminAnalysisJobActionService.requeueDeadLetterJob(
                getCurrentUserId(authentication),
                authentication.getName(),
                jobId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );

        return ApiResponse.success("분석 작업을 다시 큐에 넣었습니다.");
    }

    @GetMapping("/storage-deletion-tasks/dead-letter")
    public ApiResponse<PagedAdminStorageDeletionTaskResponse> getDeadLetterStorageDeletionTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AdminStorageDeletionTaskResponse> tasks = adminDashboardService.getDeadLetterStorageDeletionTasks(
                createPageable(page, size)
        );

        return ApiResponse.success(
                "재시도 소진 스토리지 삭제 작업 목록 조회가 완료되었습니다.",
                PagedAdminStorageDeletionTaskResponse.from(tasks)
        );
    }

    @PostMapping("/storage-deletion-tasks/{taskId}/requeue")
    public ApiResponse<Void> requeueDeadLetterStorageDeletionTask(
            @PathVariable Long taskId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminStorageDeletionTaskActionService.requeueDeadLetterTask(
                getCurrentUserId(authentication),
                authentication.getName(),
                taskId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );

        return ApiResponse.success("스토리지 삭제 작업을 다시 큐에 넣었습니다.");
    }

    @GetMapping("/password-reset-email-tasks/dead-letter")
    public ApiResponse<PagedAdminPasswordResetEmailTaskResponse> getDeadLetterPasswordResetEmailTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AdminPasswordResetEmailTaskResponse> tasks =
                adminDashboardService.getDeadLetterPasswordResetEmailTasks(
                        createPageable(page, size)
                );

        return ApiResponse.success(
                "재시도 소진 비밀번호 재설정 이메일 작업 목록 조회가 완료되었습니다.",
                PagedAdminPasswordResetEmailTaskResponse.from(tasks)
        );
    }

    @PostMapping("/password-reset-email-tasks/{taskId}/requeue")
    public ApiResponse<Void> requeueDeadLetterPasswordResetEmailTask(
            @PathVariable Long taskId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminPasswordResetEmailTaskActionService.requeueDeadLetter(
                getCurrentUserId(authentication),
                authentication.getName(),
                taskId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );
        return ApiResponse.success("비밀번호 재설정 이메일 작업을 다시 큐에 넣었습니다.");
    }

    @DeleteMapping("/results/{jobId}")
    public ApiResponse<Void> deleteResult(
            @PathVariable String jobId,
            @Valid @RequestBody AdminActionReasonRequest request,
            Authentication authentication
    ) {
        adminResultActionService.deleteResult(
                getCurrentUserId(authentication),
                authentication.getName(),
                jobId,
                request.reason(),
                currentRequestId(),
                request.incidentId()
        );

        return ApiResponse.success("분석 결과를 삭제했습니다.");
    }

    // RequestIdFilter가 응답 헤더와 모든 서버 로그에 넣은 동일한 값을 감사로그에도
    // 저장한다. 컨트롤러가 필터 밖에서 직접 호출되는 예외적인 테스트 상황만 fallback을 쓴다.
    private String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
    }

    private Long getCurrentUserId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("인증 정보에서 사용자 id를 찾을 수 없습니다.");
    }

    private Pageable createPageable(int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(size, MAX_PAGE_SIZE);

        return PageRequest.of(normalizedPage, normalizedSize);
    }
}
