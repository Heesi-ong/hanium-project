package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.presentation.dto.response.AdminStatsResponse;
import com.hanium.presentation.presentation.dto.response.AdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ResultQueryService resultQueryService;

    public AdminDashboardService(
            UserRepository userRepository,
            AnalysisJobRepository analysisJobRepository,
            ResultQueryService resultQueryService
    ) {
        this.userRepository = userRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.resultQueryService = resultQueryService;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> getUsers(Pageable pageable) {
        Page<User> users = userRepository.findAllByOrderByCreatedAtDesc(pageable);

        return users.map(user -> AdminUserSummaryResponse.of(
                user,
                analysisJobRepository.countByOwnerId(user.getId())
        ));
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                userRepository.count(),
                userRepository.countByRole(UserRole.ADMIN),
                analysisJobRepository.count(),
                analysisJobRepository.countByStatus(AnalysisStatus.COMPLETED)
        );
    }

    // 관리자가 특정 사용자의 분석 결과 목록을 조회합니다. 기존 사용자용 조회 로직
    // (ResultQueryService.getResultSummaries)을 그대로 재사용해, 정량 분석 결과 형식을
    // 임의로 바꾸지 않습니다.
    @Transactional(readOnly = true)
    public Page<ResultSummaryResponse> getUserResults(Long userId, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        return resultQueryService.getResultSummaries(userId, pageable);
    }
}
