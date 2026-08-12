package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.user.UserWithdrawalService;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserActionService {

    private final UserRepository userRepository;
    private final AdminAuditLogService adminAuditLogService;
    private final UserWithdrawalService userWithdrawalService;

    public AdminUserActionService(
            UserRepository userRepository,
            AdminAuditLogService adminAuditLogService,
            UserWithdrawalService userWithdrawalService
    ) {
        this.userRepository = userRepository;
        this.adminAuditLogService = adminAuditLogService;
        this.userWithdrawalService = userWithdrawalService;
    }

    @Transactional
    public void suspendUser(
            Long adminId,
            String adminEmail,
            Long targetUserId,
            String reason,
            String requestId,
            String incidentId
    ) {
        if (adminId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "자기 자신은 정지할 수 없습니다.");
        }

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        targetUser.suspend();

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.SUSPEND_USER,
                AdminAuditTargetType.USER,
                String.valueOf(targetUserId),
                null,
                reason,
                requestId,
                incidentId
        );
    }

    @Transactional
    public void activateUser(Long adminId, String adminEmail, Long targetUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        targetUser.activate();

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.ACTIVATE_USER,
                AdminAuditTargetType.USER,
                String.valueOf(targetUserId),
                null,
                null,
                null,
                null
        );
    }

    @Transactional
    public void forceWithdrawUser(
            Long adminId,
            String adminEmail,
            Long targetUserId,
            String reason,
            String requestId,
            String incidentId
    ) {
        if (adminId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "자기 자신은 강제 탈퇴시킬 수 없습니다.");
        }

        if (!userRepository.existsById(targetUserId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        userWithdrawalService.forceWithdraw(targetUserId);

        adminAuditLogService.record(
                adminId,
                adminEmail,
                AdminAuditAction.FORCE_WITHDRAW_USER,
                AdminAuditTargetType.USER,
                String.valueOf(targetUserId),
                null,
                reason,
                requestId,
                incidentId
        );
    }
}
