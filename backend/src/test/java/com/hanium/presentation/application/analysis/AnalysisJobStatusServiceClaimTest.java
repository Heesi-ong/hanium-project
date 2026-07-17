package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * claimNextQueuedJobs()의 순수 로직(한도 처리, 선점 성공 항목만 반환)을 mock으로 빠르게
 * 검증합니다. 실제 행 잠금이 동시 트랜잭션 사이에서 상호 배제를 제공하는지는
 * AnalysisJobStatusServiceConcurrentClaimTest(실제 H2 DB)에서 별도로 검증합니다.
 */
class AnalysisJobStatusServiceClaimTest {

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final AnalysisJobStatusService analysisJobStatusService =
            new AnalysisJobStatusService(analysisJobRepository, new AnalysisRetryProperties(3));

    @Test
    void returnsEmptyListWithoutQueryingWhenLimitIsZeroOrNegative() {
        List<AnalysisJob> result = analysisJobStatusService.claimNextQueuedJobs(0);

        assertThat(result).isEmpty();
        verify(analysisJobRepository, never())
                .findByStatusOrderByCreatedAtAscForClaim(any(), any());
    }

    @Test
    void claimsAllCandidatesReturnedByTheLockingQuery() {
        AnalysisJob first = AnalysisJob.create("job-1", 1L);
        first.enqueue(true, true);
        AnalysisJob second = AnalysisJob.create("job-2", 1L);
        second.enqueue(false, true);

        when(analysisJobRepository.findByStatusOrderByCreatedAtAscForClaim(
                eq(AnalysisStatus.QUEUED), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        List<AnalysisJob> claimed = analysisJobStatusService.claimNextQueuedJobs(5);

        assertThat(claimed).containsExactly(first, second);
        assertThat(first.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
        assertThat(second.getStatus()).isEqualTo(AnalysisStatus.BASIC_ANALYZING);
        verify(analysisJobRepository).findByStatusOrderByCreatedAtAscForClaim(
                AnalysisStatus.QUEUED, PageRequest.of(0, 5));
        verify(analysisJobRepository).saveAll(claimed);
    }

    @Test
    void excludesCandidatesThatAreNoLongerQueuedFromTheClaimedResult() {
        // 방어적 재확인: 잠금을 쥔 채로 조회했으므로 실제로는 발생하지 않아야 하지만,
        // 혹시라도 QUEUED가 아닌 후보가 섞여 들어오면 결과에서 제외합니다.
        AnalysisJob alreadyRunning = AnalysisJob.create("job-already-running", 1L);
        alreadyRunning.enqueue(true, true);
        alreadyRunning.startExecutionIfQueued();

        when(analysisJobRepository.findByStatusOrderByCreatedAtAscForClaim(
                eq(AnalysisStatus.QUEUED), any(Pageable.class)))
                .thenReturn(List.of(alreadyRunning));

        List<AnalysisJob> claimed = analysisJobStatusService.claimNextQueuedJobs(5);

        assertThat(claimed).isEmpty();
        verify(analysisJobRepository).saveAll(List.of());
    }
}
