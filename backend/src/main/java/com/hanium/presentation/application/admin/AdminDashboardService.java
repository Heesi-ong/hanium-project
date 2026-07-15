package com.hanium.presentation.application.admin;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.presentation.dto.response.AdminStatsResponse;
import com.hanium.presentation.presentation.dto.response.AdminUserSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final AnalysisJobRepository analysisJobRepository;

    public AdminDashboardService(
            UserRepository userRepository,
            AnalysisJobRepository analysisJobRepository
    ) {
        this.userRepository = userRepository;
        this.analysisJobRepository = analysisJobRepository;
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
}
