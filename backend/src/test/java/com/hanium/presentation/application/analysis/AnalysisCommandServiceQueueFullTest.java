package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1단계(대기열 포화 처리) 검증 테스트.
 *
 * <p>목표 두 가지를 확인합니다.
 * (1) 워커 대기열이 가득 찬 상태에서 분석을 요청하면, 상태를 "시작됨"으로 바꾸지 않고
 *     429(ANALYSIS_QUEUE_FULL)로 거절한다.
 * (2) 사전 점검과 실제 제출 사이의 경합으로 제출이 거부되면(RejectedExecutionException),
 *     job을 곧바로 실패 처리하고 queue-full 실패 메트릭을 남긴다.</p>
 */
class AnalysisCommandServiceQueueFullTest {

    private static final String JOB_ID = "20260708120000-abcdef01";

    private SimpleMeterRegistry meterRegistry;
    private AnalysisJobRepository analysisJobRepository;
    private AnalysisJobStatusService analysisJobStatusService;
    private AnalysisProgressService analysisProgressService;
    private ResultCommandService resultCommandService;
    private AnalysisJob analysisJob;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisJobRepository = mock(AnalysisJobRepository.class);
        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        analysisProgressService = mock(AnalysisProgressService.class);
        resultCommandService = mock(ResultCommandService.class);

        analysisJob = mock(AnalysisJob.class);
        when(analysisJob.getJobId()).thenReturn(JOB_ID);
        when(analysisJob.getOwnerId()).thenReturn(1L);
        // AnalysisStatusResponse.from()이 getStatus().getDescription()을 호출하므로 스텁이 필요합니다.
        when(analysisJob.getStatus()).thenReturn(AnalysisStatus.BASIC_ANALYZING);
        when(analysisJob.isRunning()).thenReturn(false);
        when(analysisJob.isCompleted()).thenReturn(false);
        when(analysisJob.canRun()).thenReturn(true);

        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(analysisJobRepository.saveAndFlush(analysisJob)).thenReturn(analysisJob);
    }

    private ThreadPoolTaskExecutor blockingExecutorHolder;

    @AfterEach
    void tearDown() {
        if (blockingExecutorHolder != null) {
            blockingExecutorHolder.shutdown();
        }
    }

    private AnalysisCommandService buildService(ThreadPoolTaskExecutor executor) {
        when(analysisJobStatusService.failStatus(eq(JOB_ID), anyString())).thenReturn(true);
        return new AnalysisCommandService(
                analysisJobRepository,
                mock(com.hanium.presentation.domain.user.repository.UserRepository.class),
                mock(UploadedVideoRepository.class),
                mock(VideoFileCommandService.class),
                resultCommandService,
                mock(AnalysisEngineClient.class),
                mock(VideoLlmEngineClient.class),
                mock(OpenAiClient.class),
                mock(UserRateLimiter.class),
                mock(VideoDurationProbe.class),
                mock(JobIdGenerator.class),
                analysisProgressService,
                analysisJobStatusService,
                executor,
                new AnalysisQueueProperties(100, 3),
                meterRegistry,
                new AnalysisJobValidator(new AnalysisRetryProperties(3))
        );
    }

    @Test
    void rejectsWithQueueFullWhenExecutorSaturated() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("test-analysis-worker-");
        executor.initialize();
        blockingExecutorHolder = executor;

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        // 스레드 1개를 붙잡아 active=1로 만들고(첫 작업), 두 번째 작업으로 대기열(용량 1)을 채웁니다.
        executor.execute(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        AnalysisCommandService service = buildService(executor);

        try {
            assertThatThrownBy(() -> service.runAnalysis(JOB_ID, 1L, false, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);

            // 상태를 "시작됨"으로 바꾸거나 저장하지 않았는지 확인합니다.
            verify(analysisJob, never()).startBasicAnalysis();
            verify(analysisJobRepository, never()).saveAndFlush(any());
            assertThat(meterRegistry.counter("analysis.job.rejected", "reason", "queue-full").count())
                    .isEqualTo(1.0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failsJobWhenDispatchRejectedByRace() {
        // getThreadPoolExecutor()가 null을 반환하면 사전 점검은 건너뛰고,
        // execute() 호출 시 RejectedExecutionException을 던지도록 하여 경합 상황을 재현합니다.
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        when(executor.getThreadPoolExecutor()).thenReturn(null);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("queue full"))
                .when(executor).execute(any(Runnable.class));

        AnalysisCommandService service = buildService(executor);

        // 트랜잭션 동기화가 없는 테스트 환경이라 dispatch가 즉시 실행되고 거부됩니다.
        service.runAnalysis(JOB_ID, 1L, false, false);

        verify(analysisJobStatusService, times(1))
                .failStatus(eq(JOB_ID), anyString());
        verify(analysisProgressService, times(1))
                .fail(eq(JOB_ID), anyInt(), anyString());
        assertThat(meterRegistry.counter("analysis.job.failed", "reason", "queue-full").count())
                .isEqualTo(1.0);
    }
}
