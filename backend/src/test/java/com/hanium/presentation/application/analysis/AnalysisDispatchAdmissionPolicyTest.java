package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisDispatchAdmissionPolicyTest {

    private static final Long OWNER_ID = 42L;

    private AnalysisJobRepository repository;
    private ThreadPoolTaskExecutor taskExecutor;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisJobRepository.class);
        taskExecutor = mock(ThreadPoolTaskExecutor.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void acceptsAtOneBelowBothDatabaseLimitsWithoutInspectingRemoteModeExecutor() {
        when(repository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(9L);
        when(repository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID)).thenReturn(2L);

        policy(10, 3).verify(OWNER_ID, false);

        verify(taskExecutor, never()).getThreadPoolExecutor();
    }

    @Test
    void rejectsAtGlobalBoundaryBeforeReadingOwnerOrExecutorCapacity() {
        when(repository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(10L);

        assertQueueFull(() -> policy(10, 3).verify(OWNER_ID, true));

        verify(repository, never()).countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID);
        verify(taskExecutor, never()).getThreadPoolExecutor();
        assertThat(meterRegistry.counter(
                "analysis.job.rejected", "reason", "queue-full-global"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void rejectsAtPerUserBoundaryAfterGlobalCapacityPasses() {
        when(repository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(9L);
        when(repository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID)).thenReturn(3L);

        assertQueueFull(() -> policy(10, 3).verify(OWNER_ID, true));

        verify(taskExecutor, never()).getThreadPoolExecutor();
        assertThat(meterRegistry.counter(
                "analysis.job.rejected", "reason", "queue-full-per-user"
        ).count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsLocalDispatchOnlyWhenWorkersAndExecutorQueueAreBothFull() {
        when(repository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(0L);
        when(repository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID)).thenReturn(0L);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        BlockingQueue<Runnable> queue = mock(BlockingQueue.class);
        when(taskExecutor.getThreadPoolExecutor()).thenReturn(executor);
        when(executor.getQueue()).thenReturn(queue);
        when(queue.remainingCapacity()).thenReturn(0);
        when(executor.getActiveCount()).thenReturn(2);
        when(executor.getMaximumPoolSize()).thenReturn(2);

        assertQueueFull(() -> policy(10, 3).verify(OWNER_ID, true));

        assertThat(meterRegistry.counter(
                "analysis.job.rejected", "reason", "queue-full"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void acceptsWhenLocalExecutorIsNotInitialized() {
        when(repository.countByStatus(AnalysisStatus.QUEUED)).thenReturn(0L);
        when(repository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, OWNER_ID)).thenReturn(0L);
        when(taskExecutor.getThreadPoolExecutor()).thenThrow(new IllegalStateException("not initialized"));

        assertThatCode(() -> policy(10, 3).verify(OWNER_ID, true)).doesNotThrowAnyException();
    }

    private AnalysisDispatchAdmissionPolicy policy(int globalLimit, int ownerLimit) {
        return new AnalysisDispatchAdmissionPolicy(
                repository,
                taskExecutor,
                new AnalysisQueueProperties(globalLimit, ownerLimit),
                meterRegistry
        );
    }

    private void assertQueueFull(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);
    }
}
