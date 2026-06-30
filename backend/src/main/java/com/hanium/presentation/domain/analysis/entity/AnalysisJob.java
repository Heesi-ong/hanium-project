package com.hanium.presentation.domain.analysis.entity;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "analysis_jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    private AnalysisJob(String jobId) {
        this.jobId = jobId;
        this.status = AnalysisStatus.UPLOADED;
        this.createdAt = LocalDateTime.now();
    }

    public static AnalysisJob create(String jobId) {
        return new AnalysisJob(jobId);
    }

    public void startBasicAnalysis() {
        this.status = AnalysisStatus.BASIC_ANALYZING;
        this.startedAt = LocalDateTime.now();
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
}