package com.hanium.presentation.support;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.fail;

public final class AsyncAnalysisTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLLING_INTERVAL = Duration.ofMillis(100);

    private AsyncAnalysisTestSupport() {
    }

    public static void awaitAllAnalysisJobsNotRunning(
            AnalysisJobRepository analysisJobRepository
    ) {
        awaitJobsNotRunning(
                analysisJobRepository,
                analysisJobRepository.findAll().stream()
                        .map(AnalysisJob::getJobId)
                        .toList()
        );
    }

    public static void awaitJobsNotRunning(
            AnalysisJobRepository analysisJobRepository,
            String... jobIds
    ) {
        awaitJobsNotRunning(
                analysisJobRepository,
                Arrays.asList(jobIds)
        );
    }

    private static void awaitJobsNotRunning(
            AnalysisJobRepository analysisJobRepository,
            List<String> jobIds
    ) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            boolean hasRunningJob = jobIds.stream()
                    .anyMatch(jobId -> analysisJobRepository.findByJobId(jobId)
                            .map(AnalysisJob::isRunning)
                            .orElse(false));

            if (!hasRunningJob) {
                return;
            }

            sleep();
        }

        fail("비동기 분석 작업이 제한 시간 안에 종료되지 않았습니다. jobIds=" + jobIds);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLLING_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("비동기 분석 작업 대기 중 인터럽트되었습니다.");
        }
    }
}
