package com.hanium.presentation.application.admin;

import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.presentation.dto.response.AdminAnalysisJobSummaryResponse;
import com.hanium.presentation.presentation.dto.response.AdminStatsResponse;
import com.hanium.presentation.presentation.dto.response.AdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // 행마다 countByOwnerId를 따로 부르면 페이지 크기만큼 쿼리가 나가므로(N+1),
        // 이 페이지에 있는 ownerId를 한 번에 모아 배치 집계합니다. 작업이 0건인
        // 사용자는 집계 결과에 없으므로 getOrDefault로 0을 채웁니다.
        List<Long> ownerIds = users.getContent().stream()
                .map(User::getId)
                .toList();
        Map<Long, Long> jobCountByOwnerId = analysisJobRepository.countByOwnerIdIn(ownerIds).stream()
                .collect(Collectors.toMap(
                        AnalysisJobRepository.OwnerJobCount::getOwnerId,
                        AnalysisJobRepository.OwnerJobCount::getJobCount
                ));

        return users.map(user -> AdminUserSummaryResponse.of(
                user,
                jobCountByOwnerId.getOrDefault(user.getId(), 0L)
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

    // 재시도 소진(DEAD_LETTER) 작업을 한곳에서 모아 보여줍니다. 반복 실패한 작업을 FAILED
    // 목록 속에 묻어두지 않고, 관리자가 검토·재처리 대상만 따로 확인할 수 있게 합니다.
    @Transactional(readOnly = true)
    public Page<AdminAnalysisJobSummaryResponse> getDeadLetterJobs(Pageable pageable) {
        return analysisJobRepository
                .findByStatusOrderByCreatedAtDesc(AnalysisStatus.DEAD_LETTER, pageable)
                .map(AdminAnalysisJobSummaryResponse::from);
    }
}
