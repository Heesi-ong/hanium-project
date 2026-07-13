package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여러 worker 인스턴스가 동시에 폴링해도 claimNextQueuedJobs()가 같은 QUEUED 후보를
 * 중복 선점하거나 놓치지 않는지 검증합니다.
 *
 * <p>PESSIMISTIC_WRITE(SELECT ... FOR UPDATE) 행 잠금이 실제로 동시 트랜잭션 사이에서
 * 상호 배제를 제공하는지를 실제 H2 DB와 여러 스레드로 재현해 확인합니다(단위 모킹으로는
 * 검증할 수 없는 부분입니다).</p>
 */
@SpringBootTest
class AnalysisJobStatusServiceConcurrentClaimTest {

    private static final int TOTAL_QUEUED_JOBS = 20;
    private static final int WORKER_THREAD_COUNT = 5;

    @Autowired
    private AnalysisJobStatusService analysisJobStatusService;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        analysisJobRepository.deleteAll();
    }

    @Test
    void concurrentPollersNeverClaimTheSameQueuedJobTwice() throws Exception {
        for (int i = 0; i < TOTAL_QUEUED_JOBS; i++) {
            AnalysisJob job = AnalysisJob.create("concurrent-claim-job-" + i, 1L);
            job.enqueue(true, true);
            analysisJobRepository.save(job);
        }

        ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            // 여러 "worker 폴러"가 동시에 같은 batch 한도(TOTAL_QUEUED_JOBS)로 claim을
            // 시도하는 최악의 경합 상황을 재현합니다. 원자적 선점이 제대로 동작한다면,
            // 전체 QUEUED 작업 수만큼만 정확히 나뉘어 선점되고 중복은 없어야 합니다.
            List<Future<List<AnalysisJob>>> futures = IntStream.range(0, WORKER_THREAD_COUNT)
                    .mapToObj(workerIndex -> executor.submit(() -> {
                        startLatch.await();
                        return analysisJobStatusService.claimNextQueuedJobs(TOTAL_QUEUED_JOBS);
                    }))
                    .toList();

            startLatch.countDown();

            List<String> claimedJobIds = new ArrayList<>();
            for (Future<List<AnalysisJob>> future : futures) {
                List<AnalysisJob> claimed = future.get(15, TimeUnit.SECONDS);
                claimedJobIds.addAll(claimed.stream().map(AnalysisJob::getJobId).toList());
            }

            assertThat(claimedJobIds)
                    .as("같은 작업이 두 worker에게 동시에 선점되면 안 됩니다.")
                    .doesNotHaveDuplicates();
            assertThat(claimedJobIds)
                    .as("모든 QUEUED 작업이 정확히 한 번씩 선점되어야 합니다(유실 없음).")
                    .hasSize(TOTAL_QUEUED_JOBS);

            List<AnalysisJob> allJobs = analysisJobRepository.findAll();
            assertThat(allJobs)
                    .extracting(AnalysisJob::getStatus)
                    .containsOnly(AnalysisStatus.BASIC_ANALYZING);
        } finally {
            executor.shutdownNow();
        }
    }
}
