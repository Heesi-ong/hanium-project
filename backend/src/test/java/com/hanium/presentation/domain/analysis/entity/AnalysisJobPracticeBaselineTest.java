package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.PracticeGoal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AnalysisJobPracticeBaselineTest {

    @Test
    void linksCompletedSameOwnerBaselineAndGoal() {
        AnalysisJob baseline = completedJob("baseline", 1L);
        AnalysisJob practice = AnalysisJob.create("practice", 1L);

        practice.linkPracticeBaseline(baseline, PracticeGoal.GAZE);

        assertThat(practice.getBaselineJobId()).isEqualTo("baseline");
        assertThat(practice.getPracticeGoal()).isEqualTo(PracticeGoal.GAZE);
    }

    @Test
    void rejectsIncompleteOrOtherOwnersBaseline() {
        AnalysisJob practice = AnalysisJob.create("practice", 1L);

        assertThatIllegalStateException().isThrownBy(() -> practice.linkPracticeBaseline(
                AnalysisJob.create("incomplete", 1L),
                PracticeGoal.POSTURE
        ));
        assertThatIllegalStateException().isThrownBy(() -> practice.linkPracticeBaseline(
                completedJob("other-owner", 2L),
                PracticeGoal.POSTURE
        ));
    }

    private AnalysisJob completedJob(String jobId, Long ownerId) {
        AnalysisJob job = AnalysisJob.create(jobId, ownerId);
        job.startBasicAnalysis();
        job.complete();
        return job;
    }
}
