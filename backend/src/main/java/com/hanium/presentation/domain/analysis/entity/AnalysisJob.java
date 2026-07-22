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

    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AnalysisStatus status;

    @Column(length = 500)
    private String failReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    // 분석 실행 옵션을 job에 저장합니다. 서버 재시작으로 대기 작업을 이어서 실행할 때,
    // "어떤 옵션으로 실행해야 하는지"를 DB만 보고 복원할 수 있게 하기 위함입니다.
    @Column(name = "use_video_llm", nullable = false)
    private boolean useVideoLlm = true;

    @Column(name = "use_openai", nullable = false)
    private boolean useOpenAi = true;

    // 사용자가 결과 목록에서 구분하기 위해 직접 붙이는 메모(제목)입니다. 분석 파이프라인과는
    // 무관하며, 비어있으면 목록 화면이 파일명 등으로 대체 표시합니다.
    @Column(length = 200)
    private String memo;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    protected AnalysisJob() {
    }

    private AnalysisJob(String jobId, Long ownerId) {
        this.jobId = jobId;
        this.ownerId = ownerId;
        this.status = AnalysisStatus.UPLOADED;
        this.retryCount = 0;
        this.cancelRequested = false;
        this.createdAt = LocalDateTime.now();
    }

    public static AnalysisJob create(String jobId) {
        return new AnalysisJob(jobId, null);
    }

    public static AnalysisJob create(String jobId, Long ownerId) {
        return new AnalysisJob(jobId, ownerId);
    }

    public Long getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public boolean isUseVideoLlm() {
        return useVideoLlm;
    }

    public boolean isUseOpenAi() {
        return useOpenAi;
    }

    public String getMemo() {
        return memo;
    }

    public void updateMemo(String memo) {
        this.memo = memo;
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

    // 실행 요청을 "접수"만 하고 실제 실행은 나중에(백그라운드 워커에서) 시작합니다.
    // 상태를 QUEUED로 두고 실행 옵션을 함께 저장해, 재시작 후에도 이어서 실행할 수 있게 합니다.
    // startedAt은 "접수 시각"으로 기록합니다. 대기 작업 재투입 스케줄러가 "너무 오래
    // 대기 중(=재시작으로 유실됨)"인 작업을 이 시각 기준으로 찾아냅니다.
    public void enqueue(boolean useVideoLlm, boolean useOpenAi) {
        this.status = AnalysisStatus.QUEUED;
        this.useVideoLlm = useVideoLlm;
        this.useOpenAi = useOpenAi;
        this.startedAt = LocalDateTime.now();
        this.completedAt = null;
        this.failReason = null;
        this.cancelRequested = false;
    }

    // QUEUED 상태일 때만 실행(BASIC_ANALYZING)으로 전이합니다. 재시작 복구로 같은 작업이
    // 두 번 투입되어도, 먼저 claim한 워커만 true를 받고 나머지는 false를 받아 중복 실행을
    // 막습니다. (동시 저장은 @Version 낙관적 락으로 한쪽만 성공)
    public boolean startExecutionIfQueued() {
        if (this.status != AnalysisStatus.QUEUED) {
            return false;
        }
        startBasicAnalysis();
        return true;
    }

    public void startBasicAnalysis() {
        this.status = AnalysisStatus.BASIC_ANALYZING;
        this.startedAt = LocalDateTime.now();
        this.completedAt = null;
        this.failReason = null;
        this.cancelRequested = false;
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

    // 재시도 가능 횟수를 이미 소진한 상태에서 다시 실패했을 때 호출합니다. FAILED와 달리
    // 사용자의 /retry 경로(canRetry())에서 제외되어, 관리자가 검토 후 requeueFromDeadLetter()로만
    // 재처리할 수 있습니다.
    public void deadLetter(String failReason) {
        this.status = AnalysisStatus.DEAD_LETTER;
        this.failReason = failReason;
        this.completedAt = LocalDateTime.now();
    }

    public boolean requestCancel() {
        if (!isRunning()) {
            return false;
        }

        this.cancelRequested = true;
        return true;
    }

    // QUEUED(대기) 상태는 아직 파이프라인이 시작되지 않았으므로, 실행 중 취소처럼 플래그만
    // 세우고 다음 체크포인트를 기다릴 필요 없이 그 자리에서 바로 CANCELLED로 전이합니다.
    // 이미 QUEUED가 아니면(워커가 먼저 선점해 BASIC_ANALYZING으로 넘어갔다면) false를
    // 반환해, 호출자가 "실행 중 취소" 경로로 다시 시도하도록 알립니다.
    public boolean cancelFromQueue() {
        if (!isQueued()) {
            return false;
        }

        markCancelled();
        return true;
    }

    public void markCancelled() {
        this.status = AnalysisStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void resetForRetry() {
        this.retryCount++;
        this.status = AnalysisStatus.UPLOADED;
        this.failReason = null;
        this.startedAt = null;
        this.completedAt = null;
        this.cancelRequested = false;
    }

    // 관리자가 DEAD_LETTER 작업을 검토 후 다시 실행 대기열에 올릴 때 호출합니다. 사용자
    // 재시도(resetForRetry)와 달리 retryCount를 0으로 되돌려, 관리자의 재처리 판단이
    // 기존 재시도 한도에 다시 걸리지 않고 온전한 재시도 기회를 새로 받게 합니다.
    public void requeueFromDeadLetter() {
        this.retryCount = 0;
        this.status = AnalysisStatus.UPLOADED;
        this.failReason = null;
        this.startedAt = null;
        this.completedAt = null;
        this.cancelRequested = false;
    }

    public boolean isQueued() {
        return this.status == AnalysisStatus.QUEUED;
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

    public boolean isDeadLetter() {
        return this.status == AnalysisStatus.DEAD_LETTER;
    }

    public boolean canRun() {
        return this.status == AnalysisStatus.UPLOADED;
    }

    public boolean canRetry() {
        return this.status == AnalysisStatus.FAILED
                || this.status == AnalysisStatus.CANCELLED;
    }
}
