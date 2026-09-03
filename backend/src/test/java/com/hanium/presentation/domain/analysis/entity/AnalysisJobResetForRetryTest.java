package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobResetForRetryTest {

    @Test
    void failedJobRetryConsumesRetryBudget() {
        AnalysisJob job = AnalysisJob.create("job-failed", 1L);
        job.enqueue(true, true);
        job.startBasicAnalysis();
        job.fail("엔진 오류");

        job.resetForRetry();

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.UPLOADED);
        assertThat(job.getRetryCount()).isEqualTo(1);
    }

    @Test
    void cancelledJobRetryDoesNotConsumeRetryBudget() {
        AnalysisJob job = AnalysisJob.create("job-cancelled", 1L);
        job.enqueue(true, true);
        job.startBasicAnalysis();
        job.markCancelled();

        job.resetForRetry();

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.UPLOADED);
        assertThat(job.getRetryCount()).isZero();
    }

    @Test
    void repeatedCancelRetryNeverExhaustsRetryBudget() {
        AnalysisJob job = AnalysisJob.create("job-cancel-loop", 1L);

        for (int i = 0; i < 5; i++) {
            job.enqueue(true, true);
            job.startBasicAnalysis();
            job.markCancelled();
            job.resetForRetry();
        }

        assertThat(job.getRetryCount()).isZero();
    }
}
