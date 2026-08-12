package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리자 전용 requeueDeadLetterJob()이 DEAD_LETTER 작업만 대상으로 하고, 재큐 후 정상적으로
 * 실행 대기열(QUEUED)에 접수되는지 검증합니다. 소유권 검사가 없는 것도 이 메서드의 의도된
 * 동작입니다(관리자는 특정 사용자의 소유가 아니어도 재처리할 수 있어야 함).
 */
class AnalysisCommandServiceRequeueDeadLetterTest {

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private AnalysisCommandService buildService() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("test-requeue-worker-");
        executor.initialize();

        return new AnalysisCommandService(
                analysisJobRepository,
                mock(UploadedVideoRepository.class),
                mock(VideoFileCommandService.class),
                mock(ResultCommandService.class),
                mock(AnalysisEngineClient.class),
                mock(VideoLlmEngineClient.class),
                mock(OpenAiClient.class),
                mock(UserRateLimiter.class),
                mock(VideoDurationProbe.class),
                mock(JobIdGenerator.class),
                mock(AnalysisProgressService.class),
                mock(AnalysisJobStatusService.class),
                executor,
                new AnalysisQueueProperties(100, 3),
                new SimpleMeterRegistry(),
                new AnalysisJobValidator(new AnalysisRetryProperties(3))
        );
    }

    @Test
    void requeuesDeadLetterJobBackToQueued() {
        AnalysisJob analysisJob = AnalysisJob.create("job-dlq-1", 42L);
        analysisJob.enqueue(false, true);
        analysisJob.startBasicAnalysis();
        analysisJob.deadLetter("반복 실패");
        when(analysisJobRepository.findByJobId("job-dlq-1")).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);

        AnalysisCommandService service = buildService();

        service.requeueDeadLetterJob("job-dlq-1");

        assertThat(analysisJob.getStatus().name()).isEqualTo("QUEUED");
        assertThat(analysisJob.getRetryCount()).isZero();
        assertThat(analysisJob.isUseVideoLlm()).isFalse();
        assertThat(analysisJob.isUseOpenAi()).isTrue();
    }

    @Test
    void rejectsRequeueWhenJobIsNotDeadLetter() {
        AnalysisJob analysisJob = AnalysisJob.create("job-not-dlq", 42L);
        analysisJob.enqueue(true, true);
        analysisJob.startBasicAnalysis();
        analysisJob.fail("일반 실패");
        when(analysisJobRepository.findByJobId("job-not-dlq")).thenReturn(Optional.of(analysisJob));

        AnalysisCommandService service = buildService();

        assertThatThrownBy(() -> service.requeueDeadLetterJob("job-not-dlq"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void rejectsRequeueWhenJobDoesNotExist() {
        when(analysisJobRepository.findByJobId("missing")).thenReturn(Optional.empty());

        AnalysisCommandService service = buildService();

        assertThatThrownBy(() -> service.requeueDeadLetterJob("missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_JOB_NOT_FOUND);
    }
}
