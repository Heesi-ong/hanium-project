package com.hanium.presentation.application.user;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserWithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(UserWithdrawalService.class);

    private final UserRepository userRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ResultCommandService resultCommandService;
    private final PasswordEncoder passwordEncoder;

    public UserWithdrawalService(
            UserRepository userRepository,
            AnalysisJobRepository analysisJobRepository,
            ResultCommandService resultCommandService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.resultCommandService = resultCommandService;
        this.passwordEncoder = passwordEncoder;
    }

    public void withdraw(Long userId, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        List<String> failedJobIds = deleteOwnedResults(userId);
        if (!failedJobIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.FILE_DELETE_FAILED,
                    "일부 분석 데이터 삭제에 실패해 회원탈퇴를 중단했습니다."
            );
        }

        userRepository.delete(user);
        log.info("USER_WITHDRAWAL userId={} 사용자 계정과 소유 분석 데이터를 삭제했습니다.", userId);
    }

    private List<String> deleteOwnedResults(Long userId) {
        List<AnalysisJob> analysisJobs = analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId);
        List<String> failedJobIds = new ArrayList<>();

        for (AnalysisJob analysisJob : analysisJobs) {
            String jobId = analysisJob.getJobId();
            try {
                resultCommandService.deleteResult(jobId, userId);
            } catch (RuntimeException exception) {
                failedJobIds.add(jobId);
                log.warn(
                        "USER_WITHDRAWAL_DELETE_FAILED userId={} jobId={} 소유 분석 데이터 삭제에 실패했습니다.",
                        userId,
                        jobId,
                        exception
                );
            }
        }

        return failedJobIds;
    }
}
