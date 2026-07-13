package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 워커 폴러 검증.
 *
 * <p>(1) executor에 여유가 있으면, 여유(그리고 max-batch) 한도 내에서
 *     {@code claimNextQueuedJobs()}로 이미 선점(claim)된 작업을 그대로 실행 제출한다
 *     ("조회 후 실행 제출"이 아니라 "claim 후 실행 제출").
 * (2) executor가 가득 차 있으면(여유 0), claim 시도 자체를 하지도 투입하지도 않는다.</p>
 */
class QueuedAnalysisJobPollerTest {

    private AnalysisJobStatusService analysisJobStatusService;
    private AnalysisCommandService analysisCommandService;
    private ThreadPoolTaskExecutor analysisTaskExecutor;
    private ThreadPoolExecutor threadPoolExecutor;
    private BlockingQueue<Runnable> queue;
    private QueuedAnalysisJobPoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        analysisJobStatusService = mock(AnalysisJobStatusService.class);
        analysisCommandService = mock(AnalysisCommandService.class);
        analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        threadPoolExecutor = mock(ThreadPoolExecutor.class);
        queue = mock(BlockingQueue.class);

        when(analysisTaskExecutor.getThreadPoolExecutor()).thenReturn(threadPoolExecutor);
        when(threadPoolExecutor.getQueue()).thenReturn(queue);

        poller = new QueuedAnalysisJobPoller(
                analysisJobStatusService,
                analysisCommandService,
                analysisTaskExecutor,
                10
        );
    }

    @Test
    void dispatchesClaimedJobsWhenExecutorHasHeadroom() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(0);
        when(queue.remainingCapacity()).thenReturn(20);

        AnalysisJob job1 = AnalysisJob.create("20260708120000-aaaaaaa1", 1L);
        job1.enqueue(true, true);
        job1.startExecutionIfQueued();
        AnalysisJob job2 = AnalysisJob.create("20260708120001-aaaaaaa2", 2L);
        job2.enqueue(false, true);
        job2.startExecutionIfQueued();

        when(analysisJobStatusService.claimNextQueuedJobs(anyInt()))
                .thenReturn(List.of(job1, job2));

        poller.pollAndDispatch();

        verify(analysisJobStatusService, times(1)).claimNextQueuedJobs(10);
        verify(analysisCommandService, times(1))
                .dispatchClaimedJob("20260708120000-aaaaaaa1", true, true);
        verify(analysisCommandService, times(1))
                .dispatchClaimedJob("20260708120001-aaaaaaa2", false, true);
    }

    @Test
    void limitsClaimRequestToAvailableExecutorHeadroom() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(3);
        when(queue.remainingCapacity()).thenReturn(0);

        when(analysisJobStatusService.claimNextQueuedJobs(anyInt()))
                .thenReturn(List.of());

        poller.pollAndDispatch();

        // freeSlots = (4 - 3) + 0 = 1
        verify(analysisJobStatusService, times(1)).claimNextQueuedJobs(eq(1));
    }

    @Test
    void swallowsClaimExceptionAndSkipsThisPollingCycle() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(0);
        when(queue.remainingCapacity()).thenReturn(20);

        // 행 잠금 경합으로 잠금 대기 시간을 넘기면 예외가 날 수 있습니다. 이 주기는 건너뛰고
        // 다음 주기에 다시 시도해야 하며, 폴러 스케줄러 자체가 죽으면 안 됩니다.
        when(analysisJobStatusService.claimNextQueuedJobs(anyInt()))
                .thenThrow(new RuntimeException("lock wait timeout"));

        assertThatCode(() -> poller.pollAndDispatch()).doesNotThrowAnyException();

        verify(analysisCommandService, never())
                .dispatchClaimedJob(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void doesNothingWhenExecutorIsFull() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(4);
        when(queue.remainingCapacity()).thenReturn(0);

        poller.pollAndDispatch();

        verify(analysisJobStatusService, never()).claimNextQueuedJobs(anyInt());
        verify(analysisCommandService, never())
                .dispatchClaimedJob(anyString(), anyBoolean(), anyBoolean());
    }
}
