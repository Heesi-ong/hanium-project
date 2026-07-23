package com.hanium.presentation.domain.analysis.repository;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 코치가 "저번보다 나아졌나요?" 같은 질문에 답할 때 참고하는 과거 발표 이력 조회 쿼리
 * (findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc)의 필터링/정렬/개수
 * 제한이 올바른지 검증합니다.
 */
@DataJpaTest
class AnalysisJobRepositoryHistorySummaryQueryTest {

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Test
    void excludesCurrentJobAndOrdersByCreatedAtDescUpToLimit() {
        completeJob("job-current", 1L);
        completeJob("job-past-first", 1L);
        completeJob("job-past-second", 1L);
        completeJob("job-past-third", 1L);

        List<AnalysisJob> history = analysisJobRepository
                .findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
                        1L,
                        AnalysisStatus.COMPLETED,
                        AnalysisKind.STANDARD,
                        "job-current",
                        PageRequest.of(0, 2)
                );

        assertThat(history)
                .extracting(AnalysisJob::getJobId)
                .containsExactly("job-past-third", "job-past-second");
    }

    @Test
    void excludesJobsBelongingToOtherOwners() {
        completeJob("job-current", 1L);
        completeJob("job-other-owner", 2L);

        List<AnalysisJob> history = analysisJobRepository
                .findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
                        1L,
                        AnalysisStatus.COMPLETED,
                        AnalysisKind.STANDARD,
                        "job-current",
                        PageRequest.of(0, 10)
                );

        assertThat(history).isEmpty();
    }

    @Test
    void excludesJobsThatAreNotCompleted() {
        completeJob("job-current", 1L);
        AnalysisJob queuedJob = AnalysisJob.create("job-queued", 1L);
        queuedJob.enqueue(true, true);
        analysisJobRepository.save(queuedJob);

        List<AnalysisJob> history = analysisJobRepository
                .findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
                        1L,
                        AnalysisStatus.COMPLETED,
                        AnalysisKind.STANDARD,
                        "job-current",
                        PageRequest.of(0, 10)
                );

        assertThat(history).isEmpty();
    }

    @Test
    void excludesVideoLlmReanalysisJobs() {
        completeJob("job-current", 1L);
        AnalysisJob reanalysisJob = completeJob("job-reanalysis", 1L);
        ReflectionTestUtils.setField(reanalysisJob, "analysisKind", AnalysisKind.VIDEO_LLM_REANALYSIS);
        analysisJobRepository.save(reanalysisJob);

        List<AnalysisJob> history = analysisJobRepository
                .findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
                        1L,
                        AnalysisStatus.COMPLETED,
                        AnalysisKind.STANDARD,
                        "job-current",
                        PageRequest.of(0, 10)
                );

        assertThat(history).isEmpty();
    }

    private AnalysisJob completeJob(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.enqueue(true, true);
        analysisJob.startExecutionIfQueued();
        analysisJob.complete();
        return analysisJobRepository.save(analysisJob);
    }
}
