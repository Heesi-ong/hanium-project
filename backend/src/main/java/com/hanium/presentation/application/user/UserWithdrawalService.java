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
import org.springframework.transaction.annotation.Transactional;

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

    // 여러 job을 순회하며 삭제하다가 일부가 실패하면 전체를 롤백해야, 먼저 처리된 job의
    // DB/파일이 이미 삭제된 채로 계정만 남는 반쪽짜리 탈퇴 상태를 방지할 수 있습니다.
    @Transactional
    public void withdraw(Long userId, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        deleteUserAndOwnedResults(user);
    }

    /**
     * 관리자가 대상 사용자의 비밀번호 없이 강제로 탈퇴시킵니다.
     * 관리자 권한 검증과 감사로그 기록은 호출 측(AdminUserActionService)의 책임입니다.
     */
    @Transactional
    public void forceWithdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        deleteUserAndOwnedResults(user);
    }

    private void deleteUserAndOwnedResults(User user) {
        Long userId = user.getId();
        List<String> failedJobIds = deleteOwnedResults(userId);
        if (!failedJobIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.FILE_DELETE_FAILED,
                    "일부 분석 데이터 삭제에 실패해 탈퇴를 중단했습니다."
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
                // 개별 결과 삭제 API는 사용자가 실수로 대기 작업을 지우지 못하게 QUEUED를
                // 거부한다. 하지만 회원 탈퇴에서는 이 정책을 그대로 적용하면 워커가 꺼져
                // 영원히 QUEUED인 작업 하나만 있어도 계정을 삭제할 수 없다. 아직 실행되지
                // 않은 작업은 이 트랜잭션에서 즉시 취소한 뒤 삭제한다.
                //
                // @Version이 있으므로 이 사이 워커가 먼저 선점하면 flush에서 충돌하고 전체
                // 탈퇴가 롤백된다. 실행 중인 작업은 강제 삭제하지 않고 기존 deleteResult()
                // 검증에 맡겨, 처리 스레드가 삭제된 job/asset에 결과를 다시 쓰는 race를 막는다.
                if (analysisJob.isQueued()) {
                    if (!analysisJob.cancelFromQueue()) {
                        throw new BusinessException(
                                ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED,
                                "회원 탈퇴 중 대기 작업 취소에 실패했습니다. jobId=" + jobId
                        );
                    }
                    analysisJobRepository.saveAndFlush(analysisJob);
                    log.info(
                            "USER_WITHDRAWAL_CANCEL_QUEUED userId={} jobId={} 대기 작업을 취소 후 삭제합니다.",
                            userId,
                            jobId
                    );
                }
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
