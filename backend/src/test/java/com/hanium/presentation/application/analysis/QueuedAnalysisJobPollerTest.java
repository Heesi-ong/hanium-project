package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * <p>(1) executor에 여유가 있으면, 여유(그리고 max-batch) 한도 내에서 QUEUED 작업을 가져와
 *     저장된 옵션 그대로 투입한다.
 * (2) executor가 가득 차 있으면(여유 0), DB를 조회하지도 투입하지도 않는다.</p>
 */
class QueuedAnalysisJobPollerTest {

    private AnalysisJobRepository analysisJobRepository;
    private AnalysisCommandService analysisCommandService;
    private ThreadPoolTaskExecutor analysisTaskExecutor;
    private ThreadPoolExecutor threadPoolExecutor;
    private BlockingQueue<Runnable> queue;
    private QueuedAnalysisJobPoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        analysisJobRepository = mock(AnalysisJobRepository.class);
        analysisCommandService = mock(AnalysisCommandService.class);
        analysisTaskExecutor = mock(ThreadPoolTaskExecutor.class);
        threadPoolExecutor = mock(ThreadPoolExecutor.class);
        queue = mock(BlockingQueue.class);

        when(analysisTaskExecutor.getThreadPoolExecutor()).thenReturn(threadPoolExecutor);
        when(threadPoolExecutor.getQueue()).thenReturn(queue);

        poller = new QueuedAnalysisJobPoller(
                analysisJobRepository,
                analysisCommandService,
                analysisTaskExecutor,
                10
        );
    }

    @Test
    void dispatchesQueuedJobsWhenExecutorHasHeadroom() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(0);
        when(queue.remainingCapacity()).thenReturn(20);

        AnalysisJob job1 = AnalysisJob.create("20260708120000-aaaaaaa1", 1L);
        job1.enqueue(true, true);
        AnalysisJob job2 = AnalysisJob.create("20260708120001-aaaaaaa2", 2L);
        job2.enqueue(false, true);

        when(analysisJobRepository.findByStatusOrderByCreatedAtAsc(
                eq(AnalysisStatus.QUEUED), any(Pageable.class)))
                .thenReturn(List.of(job1, job2));

        poller.pollAndDispatch();

        verify(analysisCommandService, times(1))
                .redispatchQueuedJob("20260708120000-aaaaaaa1", true, true);
        verify(analysisCommandService, times(1))
                .redispatchQueuedJob("20260708120001-aaaaaaa2", false, true);
    }

    @Test
    void doesNothingWhenExecutorIsFull() {
        when(threadPoolExecutor.getMaximumPoolSize()).thenReturn(4);
        when(threadPoolExecutor.getActiveCount()).thenReturn(4);
        when(queue.remainingCapacity()).thenReturn(0);

        poller.pollAndDispatch();

        verify(analysisJobRepository, never())
                .findByStatusOrderByCreatedAtAsc(any(), any());
        verify(analysisCommandService, never())
                .redispatchQueuedJob(anyString(), anyBoolean(), anyBoolean());
    }
}
