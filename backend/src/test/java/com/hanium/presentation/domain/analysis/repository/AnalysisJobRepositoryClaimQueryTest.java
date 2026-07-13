package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claimNextQueuedJobs()가 사용하는 조회 쿼리(findByStatusOrderByCreatedAtAscForClaim) 자체의
 * 필터링/정렬/개수 제한이 올바른지 검증합니다. 동시성(행 잠금) 검증은
 * AnalysisJobStatusServiceConcurrentClaimTest에서 별도로 다룹니다.
 */
@DataJpaTest
class AnalysisJobRepositoryClaimQueryTest {

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Test
    void returnsOnlyQueuedJobsInCreatedAtAscendingOrderUpToLimit() {
        AnalysisJob basicAnalyzing = AnalysisJob.create("job-basic-analyzing", 1L);
        basicAnalyzing.enqueue(true, true);
        basicAnalyzing.startExecutionIfQueued();
        analysisJobRepository.save(basicAnalyzing);

        AnalysisJob queuedFirst = AnalysisJob.create("job-queued-first", 1L);
        queuedFirst.enqueue(true, true);
        analysisJobRepository.save(queuedFirst);

        AnalysisJob queuedSecond = AnalysisJob.create("job-queued-second", 1L);
        queuedSecond.enqueue(true, true);
        analysisJobRepository.save(queuedSecond);

        AnalysisJob queuedThird = AnalysisJob.create("job-queued-third", 1L);
        queuedThird.enqueue(true, true);
        analysisJobRepository.save(queuedThird);

        // createdAt은 AnalysisJob.create() 시점에 LocalDateTime.now()로 고정되므로, 저장 순서와
        // createdAt 오름차순이 같음을 전제로 검증합니다.
        List<AnalysisJob> claimCandidates = analysisJobRepository.findByStatusOrderByCreatedAtAscForClaim(
                AnalysisStatus.QUEUED,
                PageRequest.of(0, 2)
        );

        assertThat(claimCandidates)
                .extracting(AnalysisJob::getJobId)
                .containsExactly("job-queued-first", "job-queued-second");
    }

    @Test
    void returnsEmptyListWhenNoQueuedJobsExist() {
        AnalysisJob completedJob = AnalysisJob.create("job-completed", 1L);
        completedJob.enqueue(true, true);
        completedJob.startExecutionIfQueued();
        completedJob.complete();
        analysisJobRepository.save(completedJob);

        List<AnalysisJob> claimCandidates = analysisJobRepository.findByStatusOrderByCreatedAtAscForClaim(
                AnalysisStatus.QUEUED,
                PageRequest.of(0, 10)
        );

        assertThat(claimCandidates).isEmpty();
    }
}
