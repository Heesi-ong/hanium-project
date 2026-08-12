package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRetryPolicyTest {

    private final AnalysisRetryPolicy policy = new AnalysisRetryPolicy();

    @Test
    void preservesBothStoredOptionsWhenOverridesAreAbsent() {
        AnalysisJob job = queuedJob(false, true);

        AnalysisRetryPolicy.RetryOptions options = policy.resolve(job, null, null);

        assertThat(options.useVideoLlm()).isFalse();
        assertThat(options.useOpenAi()).isTrue();
    }

    @Test
    void appliesOnlyTheExplicitPartialOverride() {
        AnalysisJob job = queuedJob(false, true);

        AnalysisRetryPolicy.RetryOptions options = policy.resolve(job, true, null);

        assertThat(options.useVideoLlm()).isTrue();
        assertThat(options.useOpenAi()).isTrue();
    }

    @Test
    void explicitFalseOverridesPreviouslyEnabledOptions() {
        AnalysisJob job = queuedJob(true, true);

        AnalysisRetryPolicy.RetryOptions options = policy.resolve(job, false, false);

        assertThat(options.useVideoLlm()).isFalse();
        assertThat(options.useOpenAi()).isFalse();
    }

    private AnalysisJob queuedJob(boolean useVideoLlm, boolean useOpenAi) {
        AnalysisJob job = AnalysisJob.create("20260812000000-abcdef12", 42L);
        job.enqueue(useVideoLlm, useOpenAi);
        return job;
    }
}
