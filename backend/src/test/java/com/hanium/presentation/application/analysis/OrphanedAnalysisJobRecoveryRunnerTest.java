package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "storage.result-path=${user.dir}/build/test-storage/startup-recovery/results",
        "analysis.startup-recovery.enabled=true"
})
class OrphanedAnalysisJobRecoveryRunnerTest {

    private static final String RUNNING_JOB_ID = "20260903170000-aaaaaaaa";
    private static final String RECENT_RUNNING_JOB_ID = "20260903170001-bbbbbbbb";
    private static final String COMPLETED_JOB_ID = "20260903170002-cccccccc";
    private static final String QUEUED_JOB_ID = "20260903170003-dddddddd";

    @Autowired
    private OrphanedAnalysisJobRecoveryRunner runner;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @BeforeEach
    void setUp() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        analysisJobRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        analysisJobRepository.deleteAll();
    }

    @Test
    void failsAllRunningJobsRegardlessOfHowRecentlyTheyStarted() {
        AnalysisJob oldRunningJob = runningJob(
                RUNNING_JOB_ID,
                AnalysisStatus.VIDEO_LLM_ANALYZING,
                LocalDateTime.now().minusMinutes(45)
        );
        // 워치도그와 달리 시간 임계값이 없습니다: 기동 직후엔 executor가 비어 있어
        // 방금 시작한 것으로 보이는 작업도 사실은 이전 프로세스의 orphan입니다.
        AnalysisJob recentRunningJob = runningJob(
                RECENT_RUNNING_JOB_ID,
                AnalysisStatus.BASIC_ANALYZING,
                LocalDateTime.now().minusSeconds(5)
        );
        AnalysisJob completedJob = runningJob(
                COMPLETED_JOB_ID,
                AnalysisStatus.BASIC_ANALYZING,
                LocalDateTime.now().minusMinutes(10)
        );
        completedJob.complete();
        AnalysisJob queuedJob = AnalysisJob.create(QUEUED_JOB_ID, 1L);
        queuedJob.enqueue(true, true);

        analysisJobRepository.saveAllAndFlush(
                List.of(oldRunningJob, recentRunningJob, completedJob, queuedJob)
        );

        runner.run(null);

        assertThat(findJob(RUNNING_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(findJob(RECENT_RUNNING_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(findJob(RECENT_RUNNING_JOB_ID).getFailReason()).contains("서버 재시작");
        // 종료/대기 상태는 건드리지 않습니다.
        assertThat(findJob(COMPLETED_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(findJob(QUEUED_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        Map<String, Object> failureResult = jsonFileStorage.readObjectMap(
                filePathGenerator.generateFinalResultPath(RUNNING_JOB_ID)
        );
        assertThat(failureResult.get("status")).isEqualTo("FAILED");
        assertThat(failureResult.get("failReason")).asString().contains("서버 재시작");
    }

    @Test
    void movesRetryExhaustedOrphanToDeadLetter() {
        AnalysisJob job = runningJob(
                RUNNING_JOB_ID,
                AnalysisStatus.BASIC_ANALYZING,
                LocalDateTime.now().minusMinutes(1)
        );
        ReflectionTestUtils.setField(job, "retryCount", 3);
        analysisJobRepository.saveAndFlush(job);

        runner.run(null);

        assertThat(findJob(RUNNING_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.DEAD_LETTER);
    }

    private AnalysisJob runningJob(String jobId, AnalysisStatus status, LocalDateTime startedAt) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, 1L);
        analysisJob.startBasicAnalysis();
        ReflectionTestUtils.setField(analysisJob, "status", status);
        ReflectionTestUtils.setField(analysisJob, "startedAt", startedAt);
        return analysisJob;
    }

    private AnalysisJob findJob(String jobId) {
        return analysisJobRepository.findByJobId(jobId).orElseThrow();
    }
}
