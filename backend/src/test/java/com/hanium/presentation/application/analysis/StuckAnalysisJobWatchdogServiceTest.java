package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "storage.result-path=${user.dir}/build/test-storage/stuck-watchdog/results",
        "analysis.stuck-job.max-running-minutes=30"
})
class StuckAnalysisJobWatchdogServiceTest {

    private static final String OLD_RUNNING_JOB_ID = "20260703170000-aaaaaaaa";
    private static final String RECENT_RUNNING_JOB_ID = "20260703170001-bbbbbbbb";
    private static final String COMPLETED_JOB_ID = "20260703170002-cccccccc";
    private static final String FAILED_JOB_ID = "20260703170003-dddddddd";

    @Autowired
    private StuckAnalysisJobWatchdogService watchdogService;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @MockitoBean
    private SchedulerDistributedLock schedulerDistributedLock;

    @BeforeEach
    void setUp() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        analysisJobRepository.deleteAll();
        when(schedulerDistributedLock.tryLock(eq("stuck-job-watchdog"), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        analysisJobRepository.deleteAll();
    }

    @Test
    void failStuckAnalysisJobsFailsOnlyOldRunningJobs() {
        AnalysisJob oldRunningJob = runningJob(OLD_RUNNING_JOB_ID, LocalDateTime.now().minusMinutes(45));
        AnalysisJob recentRunningJob = runningJob(RECENT_RUNNING_JOB_ID, LocalDateTime.now().minusMinutes(5));
        AnalysisJob completedJob = completedJob(COMPLETED_JOB_ID, LocalDateTime.now().minusMinutes(45));
        AnalysisJob failedJob = failedJob(FAILED_JOB_ID, LocalDateTime.now().minusMinutes(45));

        analysisJobRepository.saveAllAndFlush(
                java.util.List.of(oldRunningJob, recentRunningJob, completedJob, failedJob)
        );

        watchdogService.failStuckAnalysisJobs();

        AnalysisJob updatedOldRunningJob = findJob(OLD_RUNNING_JOB_ID);
        AnalysisJob updatedRecentRunningJob = findJob(RECENT_RUNNING_JOB_ID);
        AnalysisJob updatedCompletedJob = findJob(COMPLETED_JOB_ID);
        AnalysisJob updatedFailedJob = findJob(FAILED_JOB_ID);

        assertThat(updatedOldRunningJob.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(updatedOldRunningJob.getFailReason())
                .contains("자동으로 실패 처리");
        assertThat(updatedRecentRunningJob.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
        assertThat(updatedCompletedJob.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(updatedFailedJob.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(updatedFailedJob.getFailReason()).isEqualTo("이미 실패한 작업입니다.");

        Map<String, Object> failureResult = jsonFileStorage.readObjectMap(
                filePathGenerator.generateFinalResultPath(OLD_RUNNING_JOB_ID)
        );
        assertThat(failureResult.get("status")).isEqualTo("FAILED");
        assertThat(failureResult.get("failedStep")).isEqualTo("FAILED");
        assertThat(failureResult.get("failReason")).asString()
                .contains("자동으로 실패 처리");
    }

    @Test
    void failStuckAnalysisJobsSkipsWhenDistributedLockIsAlreadyHeld() {
        AnalysisJob oldRunningJob = runningJob(OLD_RUNNING_JOB_ID, LocalDateTime.now().minusMinutes(45));
        analysisJobRepository.saveAndFlush(oldRunningJob);
        when(schedulerDistributedLock.tryLock(eq("stuck-job-watchdog"), any(Duration.class))).thenReturn(false);

        watchdogService.failStuckAnalysisJobs();

        AnalysisJob updatedOldRunningJob = findJob(OLD_RUNNING_JOB_ID);
        assertThat(updatedOldRunningJob.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
        assertThat(updatedOldRunningJob.getFailReason()).isNull();
    }

    private AnalysisJob runningJob(String jobId, LocalDateTime startedAt) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, 1L);
        analysisJob.startBasicAnalysis();
        ReflectionTestUtils.setField(analysisJob, "startedAt", startedAt);
        return analysisJob;
    }

    private AnalysisJob completedJob(String jobId, LocalDateTime startedAt) {
        AnalysisJob analysisJob = runningJob(jobId, startedAt);
        analysisJob.complete();
        return analysisJob;
    }

    private AnalysisJob failedJob(String jobId, LocalDateTime startedAt) {
        AnalysisJob analysisJob = runningJob(jobId, startedAt);
        analysisJob.fail("이미 실패한 작업입니다.");
        return analysisJob;
    }

    private AnalysisJob findJob(String jobId) {
        return analysisJobRepository.findByJobId(jobId)
                .orElseThrow();
    }
}
