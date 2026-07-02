package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 jobId로 거의 동시에 두 번 실행 요청이 들어왔을 때 하나만 통과시키기 위한
    // 낙관적 락(optimistic lock)입니다. 두 트랜잭션이 동시에 이 엔티티를 저장하려 하면
    // 버전이 이미 바뀐 쪽은 실패(ObjectOptimisticLockingFailureException)합니다.
    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 50)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AnalysisStatus status;

    @Column(length = 500)
    private String failReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    protected AnalysisJob() {
    }

    private AnalysisJob(String jobId) {
        this.jobId = jobId;
        this.status = AnalysisStatus.UPLOADED;
        this.createdAt = LocalDateTime.now();
    }

    public static AnalysisJob create(String jobId) {
        return new AnalysisJob(jobId);
    }

    public Long getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void startBasicAnalysis() {
        this.status = AnalysisStatus.BASIC_ANALYZING;
        this.startedAt = LocalDateTime.now();
        this.completedAt = null;
        this.failReason = null;
    }

    public void startVideoLlmAnalysis() {
        this.status = AnalysisStatus.VIDEO_LLM_ANALYZING;
    }

    public void startCompacting() {
        this.status = AnalysisStatus.COMPACTING;
    }

    public void startOpenAiGenerating() {
        this.status = AnalysisStatus.OPENAI_GENERATING;
    }

    public void startMergingResult() {
        this.status = AnalysisStatus.MERGING_RESULT;
    }

    public void complete() {
        this.status = AnalysisStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String failReason) {
        this.status = AnalysisStatus.FAILED;
        this.failReason = failReason;
        this.completedAt = LocalDateTime.now();
    }

    public void resetForRetry() {
        this.status = AnalysisStatus.UPLOADED;
        this.failReason = null;
        this.startedAt = null;
        this.completedAt = null;
    }

    public boolean isRunning() {
        return this.status == AnalysisStatus.BASIC_ANALYZING
                || this.status == AnalysisStatus.VIDEO_LLM_ANALYZING
                || this.status == AnalysisStatus.COMPACTING
                || this.status == AnalysisStatus.OPENAI_GENERATING
                || this.status == AnalysisStatus.MERGING_RESULT;
    }

    public boolean isCompleted() {
        return this.status == AnalysisStatus.COMPLETED;
    }

    public boolean isFailed() {
        return this.status == AnalysisStatus.FAILED;
    }

    public boolean canRun() {
        return this.status == AnalysisStatus.UPLOADED;
    }

    public boolean canRetry() {
        return this.status == AnalysisStatus.FAILED;
    }
}