package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// AnalysisCommandService(1000줄 이상)에서 상태 전이 검증만 분리했다(2026-08-03 서비스화
// 점검 P2-02). 분리 전에는 이 검증 로직만 독립적으로 테스트하려면 AnalysisCommandService
// 전체의 무거운 mock 세트(10개 이상 협력자)를 구성해야 했다 — 이 테스트가 분리의 실제
// 목적(독립 테스트 가능성)을 보여준다.
class AnalysisJobValidatorTest {

    private static final Long OWNER_ID = 1L;
    private static final int MAX_RETRY_COUNT = 3;

    private AnalysisJobValidator validator;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        validator = new AnalysisJobValidator(new AnalysisRetryProperties(MAX_RETRY_COUNT));
        analysisJob = mock(AnalysisJob.class);
        when(analysisJob.getJobId()).thenReturn("job-1");
        when(analysisJob.getOwnerId()).thenReturn(OWNER_ID);
        when(analysisJob.getStatus()).thenReturn(AnalysisStatus.BASIC_ANALYZING);
    }

    @Test
    void validateRunnablePassesForARunnableJob() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);

        assertThatCode(() -> validator.validateRunnable(analysisJob)).doesNotThrowAnyException();
    }

    @Test
    void validateRunnableRejectsAnAlreadyRunningJob() {
        when(analysisJob.isRunning()).thenReturn(true);

        assertThatThrownBy(() -> validator.validateRunnable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);
    }

    @Test
    void validateRunnableRejectsAnAlreadyQueuedJob() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(true);

        assertThatThrownBy(() -> validator.validateRunnable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);
    }

    @Test
    void validateRunnableRejectsAnAlreadyCompletedJob() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(true);

        assertThatThrownBy(() -> validator.validateRunnable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_ALREADY_COMPLETED);
    }

    @Test
    void validateRunnableRejectsAJobThatCannotRunFromItsCurrentStatus() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(false);

        assertThatThrownBy(() -> validator.validateRunnable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void validateRetryablePassesForARetryableJob() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.isDeadLetter()).thenReturn(false);
        when(analysisJob.canRetry()).thenReturn(true);
        when(analysisJob.getRetryCount()).thenReturn(0);

        assertThatCode(() -> validator.validateRetryable(analysisJob)).doesNotThrowAnyException();
    }

    // DEAD_LETTER는 canRetry()에서 이미 제외되지만, 재시도 한도 소진과 같은 오류
    // 코드(ANALYSIS_RETRY_LIMIT_EXCEEDED)로 응답해야 한다 — AnalysisCommandService에
    // 있던 동작을 그대로 옮긴 것이다.
    @Test
    void validateRetryableTreatsDeadLetterAsRetryLimitExceeded() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.isDeadLetter()).thenReturn(true);

        assertThatThrownBy(() -> validator.validateRetryable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED);
    }

    @Test
    void validateRetryableRejectsAJobThatCannotBeRetriedFromItsCurrentStatus() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.isDeadLetter()).thenReturn(false);
        when(analysisJob.canRetry()).thenReturn(false);

        assertThatThrownBy(() -> validator.validateRetryable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void validateRetryableRejectsAJobThatExhaustedItsRetryBudget() {
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.isDeadLetter()).thenReturn(false);
        when(analysisJob.canRetry()).thenReturn(true);
        when(analysisJob.getRetryCount()).thenReturn(MAX_RETRY_COUNT);

        assertThatThrownBy(() -> validator.validateRetryable(analysisJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED);
    }

    @Test
    void validateOwnershipPassesWhenOwnerIdMatches() {
        assertThatCode(() -> validator.validateOwnership(analysisJob, OWNER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void validateOwnershipRejectsAMismatchedOwnerId() {
        assertThatThrownBy(() -> validator.validateOwnership(analysisJob, OWNER_ID + 1))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
    }
}
