package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobDeadLetterTest {

    @Test
    void deadLetterTransitionsToDeadLetterStatusWithFailReason() {
        AnalysisJob analysisJob = AnalysisJob.create("job-1", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();

        analysisJob.deadLetter("엔진 호출 반복 실패");

        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.DEAD_LETTER);
        assertThat(analysisJob.getFailReason()).isEqualTo("엔진 호출 반복 실패");
        assertThat(analysisJob.getCompletedAt()).isNotNull();
        assertThat(analysisJob.isDeadLetter()).isTrue();
    }

    @Test
    void deadLetterJobCannotBeRetriedByUser() {
        AnalysisJob analysisJob = AnalysisJob.create("job-2", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.deadLetter("반복 실패");

        assertThat(analysisJob.canRetry()).isFalse();
    }

    @Test
    void requeueFromDeadLetterResetsRetryCountAndReturnsToUploaded() {
        AnalysisJob analysisJob = AnalysisJob.create("job-3", 1L);
        analysisJob.enqueue(true, true);
        analysisJob.deadLetter("1차 실패");
        analysisJob.resetForRetry(); // retryCount=1, UPLOADED
        analysisJob.enqueue(true, true);
        analysisJob.deadLetter("2차 실패"); // retryCount는 여전히 1

        analysisJob.requeueFromDeadLetter();

        assertThat(analysisJob.getStatus()).isEqualTo(AnalysisStatus.UPLOADED);
        assertThat(analysisJob.getRetryCount()).isZero();
        assertThat(analysisJob.getFailReason()).isNull();
        assertThat(analysisJob.getCompletedAt()).isNull();
        assertThat(analysisJob.canRun()).isTrue();
    }
}
