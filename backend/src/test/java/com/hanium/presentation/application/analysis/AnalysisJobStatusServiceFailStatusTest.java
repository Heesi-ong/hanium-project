package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * failStatus()가 재시도 소진 여부에 따라 FAILED와 DEAD_LETTER 중 어디로 전이하는지 검증합니다.
 * 사용자 재시도(retryAnalysis)가 막히는 시점(retryCount >= maxCount)과 정확히 같은 시점에
 * DEAD_LETTER로 전이해야, "더 이상 사용자가 스스로 재시도할 수 없는 실패"만 DLQ에 모입니다.
 */
class AnalysisJobStatusServiceFailStatusTest {

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final AnalysisJobStatusService analysisJobStatusService =
            new AnalysisJobStatusService(analysisJobRepository, new AnalysisRetryProperties(3));

    @Test
    void marksJobAsFailedWhenRetriesRemain() {
        AnalysisJob analysisJob = AnalysisJob.create("job-1", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();
        when(analysisJobRepository.findByJobId("job-1")).thenReturn(Optional.of(analysisJob));

        analysisJobStatusService.failStatus("job-1", "엔진 오류");

        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(analysisJobRepository).save(analysisJob);
    }

    @Test
    void marksJobAsDeadLetterWhenRetryCountAlreadyReachedMax() {
        AnalysisJob analysisJob = AnalysisJob.create("job-2", 1L);
        for (int i = 0; i < 3; i++) {
            analysisJob.enqueue(true, true);
            analysisJob.startBasicAnalysis();
            analysisJob.resetForRetry(); // retryCount: 1, 2, 3
        }
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();
        assertThat(analysisJob.getRetryCount()).isEqualTo(3);
        when(analysisJobRepository.findByJobId("job-2")).thenReturn(Optional.of(analysisJob));

        analysisJobStatusService.failStatus("job-2", "반복 실패");

        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.DEAD_LETTER);
        assertThat(analysisJob.getFailReason()).isEqualTo("반복 실패");
        verify(analysisJobRepository).save(analysisJob);
    }

    @Test
    void doesNothingWhenJobNotFound() {
        when(analysisJobRepository.findByJobId("missing")).thenReturn(Optional.empty());

        analysisJobStatusService.failStatus("missing", "사유");

        verify(analysisJobRepository, org.mockito.Mockito.never()).save(any());
    }
}
