package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대기 작업 재투입 스케줄러 검증.
 *
 * <p>(1) 분산 락을 얻으면, 오래된 QUEUED 작업을 저장된 실행 옵션 그대로 다시 투입한다.
 * (2) 분산 락을 얻지 못하면(다른 인스턴스가 이미 실행 중), 아무 작업도 투입하지 않는다.</p>
 */
class QueuedAnalysisJobResumeServiceTest {

    private static final String JOB_ID = "20260708120000-abcdef01";

    private AnalysisJobRepository analysisJobRepository;
    private AnalysisCommandService analysisCommandService;
    private SchedulerDistributedLock schedulerDistributedLock;
    private QueuedAnalysisJobResumeService service;

    @BeforeEach
    void setUp() {
        analysisJobRepository = mock(AnalysisJobRepository.class);
        analysisCommandService = mock(AnalysisCommandService.class);
        schedulerDistributedLock = mock(SchedulerDistributedLock.class);
        service = new QueuedAnalysisJobResumeService(
                analysisJobRepository,
                analysisCommandService,
                schedulerDistributedLock,
                60,
                2
        );
    }

    @Test
    void resumesStaleQueuedJobWithPersistedOptions() {
        when(schedulerDistributedLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);

        AnalysisJob job = AnalysisJob.create(JOB_ID, 1L);
        job.enqueue(true, false); // useVideoLlm=true, useOpenAi=false 로 저장

        when(analysisJobRepository.findByStatusAndStartedAtBefore(
                eq(AnalysisStatus.QUEUED), any(LocalDateTime.class)))
                .thenReturn(List.of(job));

        service.resumeStaleQueuedJobs();

        verify(analysisCommandService, times(1))
                .redispatchQueuedJob(JOB_ID, true, false);
    }

    @Test
    void skipsWhenDistributedLockNotAcquired() {
        when(schedulerDistributedLock.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        service.resumeStaleQueuedJobs();

        verify(analysisJobRepository, never())
                .findByStatusAndStartedAtBefore(any(), any());
        verify(analysisCommandService, never())
                .redispatchQueuedJob(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // resume-cron 기본값(매 분)보다 짧아야 하는 락 TTL이 실수로 다시 분 단위로(Duration.ofMinutes)
    // 해석되지 않는지 확인한다. 예전 버그(TTL 2분 > 실행 간격 1분)가 재발하면 이 값이
    // Duration.ofMinutes(45)(45분!)로 잘못 넘어가게 되므로, 정확히 초 단위인지 검증한다
    // (2026-07-23 코드 리뷰 P1-05).
    @Test
    void passesLockTtlAsSecondsNotMinutes() {
        QueuedAnalysisJobResumeService serviceWithDefaultTtl = new QueuedAnalysisJobResumeService(
                analysisJobRepository,
                analysisCommandService,
                schedulerDistributedLock,
                60,
                45
        );
        when(schedulerDistributedLock.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        serviceWithDefaultTtl.resumeStaleQueuedJobs();

        verify(schedulerDistributedLock).tryLock("queued-job-resume", Duration.ofSeconds(45));
    }
}
