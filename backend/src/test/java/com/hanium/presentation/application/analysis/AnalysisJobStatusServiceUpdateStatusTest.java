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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalysisJobStatusService#updateStatus} 상태 가드 검증.
 *
 * <p>updateStatus로 전이하는 대상은 모두 "실행 중" 하위 상태이므로, 워치도그가 먼저
 * 실패 처리했거나 취소가 확정된 뒤 좀비 파이프라인 스레드가 뒤늦게 호출해도 종료 상태를
 * running으로 되살리지 않아야 합니다.</p>
 */
class AnalysisJobStatusServiceUpdateStatusTest {

    private static final String JOB_ID = "20260903120000-abcdef01";

    private AnalysisJobStatusService newService(AnalysisJobRepository repository) {
        return new AnalysisJobStatusService(repository, new AnalysisRetryProperties(3));
    }

    @Test
    void appliesTransitionWhileJobIsRunning() {
        AnalysisJobRepository repository = mock(AnalysisJobRepository.class);
        AnalysisJob job = AnalysisJob.create(JOB_ID, 1L);
        job.enqueue(true, true);
        job.startBasicAnalysis();
        when(repository.findByJobId(JOB_ID)).thenReturn(Optional.of(job));

        newService(repository).updateStatus(JOB_ID, AnalysisStatus.VIDEO_LLM_ANALYZING);

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.VIDEO_LLM_ANALYZING);
        verify(repository).save(job);
    }

    @Test
    void doesNotResurrectJobAlreadyFailedByWatchdog() {
        AnalysisJobRepository repository = mock(AnalysisJobRepository.class);
        AnalysisJob job = AnalysisJob.create(JOB_ID, 1L);
        job.enqueue(true, true);
        job.startBasicAnalysis();
        job.fail("워치도그가 먼저 실패 처리했습니다.");
        when(repository.findByJobId(JOB_ID)).thenReturn(Optional.of(job));

        newService(repository).updateStatus(JOB_ID, AnalysisStatus.COMPACTING);

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(repository, never()).save(any());
    }

    @Test
    void doesNotResurrectJobAlreadyCancelled() {
        AnalysisJobRepository repository = mock(AnalysisJobRepository.class);
        AnalysisJob job = AnalysisJob.create(JOB_ID, 1L);
        job.enqueue(true, true);
        job.startBasicAnalysis();
        job.markCancelled();
        when(repository.findByJobId(JOB_ID)).thenReturn(Optional.of(job));

        newService(repository).updateStatus(JOB_ID, AnalysisStatus.MERGING_RESULT);

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(repository, never()).save(any());
    }
}
