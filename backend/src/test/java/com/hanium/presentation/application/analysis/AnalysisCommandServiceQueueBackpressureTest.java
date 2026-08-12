package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB에 쌓인 QUEUED 작업 수 기준의 백프레셔 검증.
 *
 * <p>api/worker 분리 모드(dispatch.local-on-run=false)에서는 로컬 executor 포화 검사가
 * 동작하지 않으므로, 워커가 느리거나 꺼져 있어도 새 요청을 막는 유일한 방어선은 이 검사입니다.
 * 그래서 dispatch.local-on-run 값과 무관하게 항상 적용되어야 합니다.</p>
 */
class AnalysisCommandServiceQueueBackpressureTest {

    private static final String JOB_ID = "20260708120000-abcdef01";
    private static final Long OWNER_ID = 1L;

    private SimpleMeterRegistry meterRegistry;
    private AnalysisJobRepository analysisJobRepository;
    private ThreadPoolTaskExecutor analysisTaskExecutor;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisJobRepository = mock(AnalysisJobRepository.class);
        analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);

        analysisJob = mock(AnalysisJob.class);
        when(analysisJob.getJobId()).thenReturn(JOB_ID);
        when(analysisJob.getOwnerId()).thenReturn(OWNER_ID);
        when(analysisJob.getStatus()).thenReturn(AnalysisStatus.BASIC_ANALYZING);
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isQueued()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);

        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);
    }

    private AnalysisCommandService buildService(
            AnalysisQueueProperties analysisQueueProperties,
            boolean localDispatchOnRun
    ) {
        AnalysisCommandService service = new AnalysisCommandService(
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
                analysisTaskExecutor,
                analysisQueueProperties,
                meterRegistry,
                new AnalysisJobValidator(new AnalysisRetryProperties(3))
        );

        ReflectionTestUtils.setField(
                service, "localDispatchOnRun", localDispatchOnRun
        );

        return service;
    }

    @Test
    void rejectsWithQueueFullWhenGlobalQueuedLimitReached() {
        when(analysisJobRepository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(2L);

        AnalysisCommandService service = buildService(
                new AnalysisQueueProperties(2, 100),
                false
        );

        assertThatThrownBy(() -> service.runAnalysis(JOB_ID, OWNER_ID, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);

        verify_neverEnqueued();
        assertThat(meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-global").count())
                .isEqualTo(1.0);
    }

    @Test
    void rejectsWithQueueFullWhenPerUserQueuedLimitReached() {
        when(analysisJobRepository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(0L);
        when(analysisJobRepository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID))
                .thenReturn(3L);

        AnalysisCommandService service = buildService(
                new AnalysisQueueProperties(100, 3),
                false
        );

        assertThatThrownBy(() -> service.runAnalysis(JOB_ID, OWNER_ID, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);

        verify_neverEnqueued();
        assertThat(meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-per-user").count())
                .isEqualTo(1.0);
    }

    @Test
    void backpressureIsEnforcedBeforeLocalExecutorCheckWhenDispatchIsLocal() {
        // localDispatchOnRun=true(monolith)에서도 DB 기준 백프레셔가 먼저 걸려야 합니다.
        // 이 검사가 executor 포화 검사보다 먼저 실행되어 즉시 예외를 던진다면,
        // getThreadPoolExecutor()는 아예 호출되지 않아야 합니다.
        when(analysisJobRepository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(5L);

        AnalysisCommandService service = buildService(
                new AnalysisQueueProperties(5, 100),
                true
        );

        assertThatThrownBy(() -> service.runAnalysis(JOB_ID, OWNER_ID, false, false))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);

        verify_neverEnqueued();
        verify(analysisTaskExecutor, never()).getThreadPoolExecutor();
    }

    private void verify_neverEnqueued() {
        verify(analysisJob, never()).enqueue(anyBoolean(), anyBoolean());
        verify(analysisJobRepository, never()).saveAndFlush(any());
    }
}
