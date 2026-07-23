package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AnalysisJobVideoAssetLinkTest {

    private static final String IDEMPOTENCY_HASH = "a".repeat(64);

    @Test
    void linksVideoAssetOnceAndAllowsIdempotentRelink() {
        AnalysisJob job = AnalysisJob.create("job-video-asset", 1L);

        job.linkVideoAsset(10L);
        job.linkVideoAsset(10L);

        assertThat(job.getVideoAssetId()).isEqualTo(10L);
    }

    @Test
    void rejectsNullOrReplacingLinkedVideoAsset() {
        AnalysisJob job = AnalysisJob.create("job-video-asset", 1L);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> job.linkVideoAsset(null));

        job.linkVideoAsset(10L);

        assertThatIllegalStateException()
                .isThrownBy(() -> job.linkVideoAsset(11L));
    }

    @Test
    void standardJobDefaultsLineageAndPersistsGenerationMode() {
        AnalysisJob job = AnalysisJob.create("job-video-mode", 1L);

        job.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);

        assertThat(job.getAnalysisKind()).isEqualTo(AnalysisKind.STANDARD);
        assertThat(job.getSourceJobId()).isNull();
        assertThat(job.getVideoLlmGenerationMode()).isEqualTo(VideoLlmGenerationMode.FALLBACK);
    }

    @Test
    void createsImmutableVideoLlmReanalysisFromCompletedFallbackSource() {
        AnalysisJob sourceJob = completedFallbackSource();

        AnalysisJob reanalysisJob = AnalysisJob.createVideoLlmReanalysis(
                "job-video-reanalysis",
                sourceJob,
                IDEMPOTENCY_HASH
        );

        assertThat(reanalysisJob.getOwnerId()).isEqualTo(sourceJob.getOwnerId());
        assertThat(reanalysisJob.getVideoAssetId()).isEqualTo(sourceJob.getVideoAssetId());
        assertThat(reanalysisJob.getAnalysisKind()).isEqualTo(AnalysisKind.VIDEO_LLM_REANALYSIS);
        assertThat(reanalysisJob.getSourceJobId()).isEqualTo(sourceJob.getJobId());
        assertThat(reanalysisJob.getReanalysisIdempotencyKeyHash()).isEqualTo(IDEMPOTENCY_HASH);
        assertThat(reanalysisJob.getVideoLlmGenerationMode()).isNull();
    }

    @Test
    void rejectsReanalysisWhenSourceIsNotCompletedFallbackWithAsset() {
        AnalysisJob incompleteSource = AnalysisJob.create("job-incomplete-source", 1L);
        incompleteSource.linkVideoAsset(10L);
        incompleteSource.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);

        assertThatIllegalStateException()
                .isThrownBy(() -> AnalysisJob.createVideoLlmReanalysis(
                        "job-invalid-reanalysis",
                        incompleteSource,
                        IDEMPOTENCY_HASH
                ));

        AnalysisJob realSource = AnalysisJob.create("job-real-source", 1L);
        realSource.linkVideoAsset(10L);
        realSource.startBasicAnalysis();
        realSource.complete();
        realSource.recordVideoLlmGenerationMode(VideoLlmGenerationMode.REAL);

        assertThatIllegalStateException()
                .isThrownBy(() -> AnalysisJob.createVideoLlmReanalysis(
                        "job-invalid-reanalysis",
                        realSource,
                        IDEMPOTENCY_HASH
                ));
    }

    @Test
    void rejectsRawOrMalformedIdempotencyHash() {
        AnalysisJob sourceJob = completedFallbackSource();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AnalysisJob.createVideoLlmReanalysis(
                        "job-invalid-reanalysis",
                        sourceJob,
                        "raw-idempotency-key"
                ));
    }

    private AnalysisJob completedFallbackSource() {
        AnalysisJob sourceJob = AnalysisJob.create("job-fallback-source", 1L);
        sourceJob.linkVideoAsset(10L);
        sourceJob.startBasicAnalysis();
        sourceJob.complete();
        sourceJob.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);
        return sourceJob;
    }
}
