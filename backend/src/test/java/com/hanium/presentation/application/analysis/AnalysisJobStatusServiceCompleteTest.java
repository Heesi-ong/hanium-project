package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisJobStatusServiceCompleteTest {

    @Test
    void completeStatusPersistsVideoLlmGenerationModeWithCompletedState() {
        AnalysisJobRepository repository = mock(AnalysisJobRepository.class);
        AnalysisJob job = AnalysisJob.create("job-complete-mode", 1L);
        when(repository.findByJobId(job.getJobId())).thenReturn(Optional.of(job));
        AnalysisJobStatusService service = new AnalysisJobStatusService(
                repository,
                new AnalysisRetryProperties(3)
        );

        service.completeStatus(job.getJobId(), VideoLlmGenerationMode.REAL);

        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(job.getVideoLlmGenerationMode()).isEqualTo(VideoLlmGenerationMode.REAL);
        verify(repository).save(job);
    }
}
