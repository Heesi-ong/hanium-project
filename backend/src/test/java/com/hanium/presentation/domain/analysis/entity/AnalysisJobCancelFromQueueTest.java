package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobCancelFromQueueTest {

    @Test
    void cancelFromQueueTransitionsQueuedJobToCancelledImmediately() {
        AnalysisJob analysisJob = AnalysisJob.create("job-1", 1L);
        analysisJob.enqueue(true, true);

        boolean cancelled = analysisJob.cancelFromQueue();

        assertThat(cancelled).isTrue();
        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(analysisJob.getCompletedAt()).isNotNull();
    }

    @Test
    void cancelFromQueueFailsWhenJobIsNotQueued() {
        AnalysisJob analysisJob = AnalysisJob.create("job-2", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();

        boolean cancelled = analysisJob.cancelFromQueue();

        assertThat(cancelled).isFalse();
        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
    }

    @Test
    void cancelFromQueueDoesNotAffectRequestCancelBehaviorForRunningJobs() {
        AnalysisJob analysisJob = AnalysisJob.create("job-3", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();

        boolean fromQueueResult = analysisJob.cancelFromQueue();
        boolean requestCancelResult = analysisJob.requestCancel();

        assertThat(fromQueueResult).isFalse();
        assertThat(requestCancelResult).isTrue();
        assertThat(analysisJob.isCancelRequested()).isTrue();
        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
    }
}
