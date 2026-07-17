package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.AnalysisStep;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisUploadResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class AnalysisCommandService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCommandService.class);

    private enum VideoLlmSkipReason {
        DISABLED(
                "이 작업은 Video LLM 분석 없이 진행하도록 설정되었습니다.",
                "Video LLM 분석 비활성화",
                "사용자 설정으로 Video LLM 분석이 생략되었습니다."
        ),
        MONTHLY_BUDGET_EXCEEDED(
                "이번 달 Video LLM 분석 호출 한도를 초과해 이 작업은 Video LLM 분석 없이 처리되었습니다.",
                "Video LLM 월간 한도 초과",
                "월간 호출 한도 초과로 Video LLM 분석이 생략되었습니다."
        );

        private final String visualDelivery;
        private final String mainStrength;
        private final String mainWeakness;

        VideoLlmSkipReason(String visualDelivery, String mainStrength, String mainWeakness) {
            this.visualDelivery = visualDelivery;
            this.mainStrength = mainStrength;
            this.mainWeakness = mainWeakness;
        }
    }

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoFileCommandService videoFileCommandService;
    private final ResultCommandService resultCommandService;
    private final AnalysisEngineClient analysisEngineClient;
    private final VideoLlmEngineClient videoLlmEngineClient;
    private final OpenAiClient openAiClient;
    private final UserRateLimiter userRateLimiter;
    private final VideoDurationProbe videoDurationProbe;
    private final JobIdGenerator jobIdGenerator;
    private final AnalysisProgressService analysisProgressService;
    private final AnalysisJobStatusService analysisJobStatusService;
    private final ThreadPoolTaskExecutor analysisTaskExecutor;

    // /run이 이 인스턴스의 로컬 executor로 즉시 투입할지 여부. 기본값 true(=monolith, 현행 동작).
    // api/worker 분리 배포에서는 false로 두고, 워커 폴러(QueuedAnalysisJobPoller)가 QUEUED를 소비합니다.
    // 필드 초기값을 true로 둬서, 스프링 컨텍스트 없이 new로 생성하는 단위 테스트에서도 기존처럼 즉시 투입됩니다.
    @Value("${analysis.dispatch.local-on-run:true}")
    private boolean localDispatchOnRun = true;

    // 작업 1건이 시작부터 끝까지 쓸 수 있는 최대 시간(분). 이 시간을 넘기면 각 단계 사이의
    // 체크포인트에서 감지해 자동으로 실패 처리합니다. 30분 워치도그(사후 복구)와 달리, 이건
    // 실행 중에 능동적으로 예산을 강제합니다. (watchdog max-running-minutes보다 작게 두세요.)
    @Value("${analysis.job.timeout-minutes:20}")
    private long jobTimeoutMinutes = 20;
    private final AnalysisRetryProperties analysisRetryProperties;
    private final AnalysisQueueProperties analysisQueueProperties;
    private final MeterRegistry meterRegistry;

    public AnalysisCommandService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoFileCommandService videoFileCommandService,
            ResultCommandService resultCommandService,
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient,
            OpenAiClient openAiClient,
            UserRateLimiter userRateLimiter,
            VideoDurationProbe videoDurationProbe,
            JobIdGenerator jobIdGenerator,
            AnalysisProgressService analysisProgressService,
            AnalysisJobStatusService analysisJobStatusService,
            ThreadPoolTaskExecutor analysisTaskExecutor,
            AnalysisRetryProperties analysisRetryProperties,
            AnalysisQueueProperties analysisQueueProperties,
            MeterRegistry meterRegistry
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoFileCommandService = videoFileCommandService;
        this.resultCommandService = resultCommandService;
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.openAiClient = openAiClient;
        this.userRateLimiter = userRateLimiter;
        this.videoDurationProbe = videoDurationProbe;
        this.jobIdGenerator = jobIdGenerator;
        this.analysisProgressService = analysisProgressService;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.analysisRetryProperties = analysisRetryProperties;
        this.analysisQueueProperties = analysisQueueProperties;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public AnalysisUploadResponse uploadVideo(MultipartFile file, Long ownerId) {
        String jobId = jobIdGenerator.generate();

        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        AnalysisJob savedJob = analysisJobRepository.save(analysisJob);

        StoredVideoInfo storedVideoInfo = videoFileCommandService.store(
                new VideoUploadCommand(jobId, file)
        );

        UploadedVideo uploadedVideo = UploadedVideo.create(
                jobId,
                storedVideoInfo.originalFileName(),
                storedVideoInfo.storedFilePath(),
                storedVideoInfo.fileType(),
                storedVideoInfo.fileSize()
        );

        uploadedVideoRepository.save(uploadedVideo);

        return AnalysisUploadResponse.of(
                savedJob.getJobId(),
                savedJob.getStatus(),
                storedVideoInfo.originalFileName(),
                storedVideoInfo.storedFilePath(),
                storedVideoInfo.fileSize()
        );
    }

    // 분석 실행/재시도는 더 이상 파이프라인이 끝날 때까지 기다리지 않습니다.
    // "시작 상태로 전환하고 커밋"까지만 이 트랜잭션에서 처리하고, 실제 무거운 작업
    // (analysis-engine/video-llm-engine/OpenAI 호출)은 트랜잭션이 커밋된 뒤
    // 백그라운드 스레드에서 실행합니다. 프론트는 이미 /run 호출 후 /status를
    // 폴링하는 구조라 이 변경으로 화면 쪽 코드를 바꿀 필요가 없습니다.
    @Transactional
    public AnalysisStatusResponse runAnalysis(
            String jobId,
            Long ownerId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateOwnership(analysisJob, ownerId);
        validateRunnable(analysisJob);

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi, "run");
    }

    @Transactional
    public AnalysisStatusResponse retryAnalysis(
            String jobId,
            Long ownerId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateOwnership(analysisJob, ownerId);
        validateRetryable(analysisJob);
        analysisJob.resetForRetry();

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi, "retry");
    }

    // 관리자 전용 재처리입니다. DEAD_LETTER(재시도 소진) 작업만 대상이며, 소유권 검사는 하지
    // 않습니다(관리자는 모든 사용자의 작업을 다룰 수 있음). 업로드 당시 선택했던 옵션
    // (useVideoLlm/useOpenAi)을 그대로 유지해 재실행합니다.
    @Transactional
    public AnalysisStatusResponse requeueDeadLetterJob(String jobId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!analysisJob.isDeadLetter()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "재시도 소진(DEAD_LETTER) 상태의 분석 작업만 재처리할 수 있습니다. status="
                            + analysisJob.getStatus()
            );
        }

        boolean useVideoLlm = analysisJob.isUseVideoLlm();
        boolean useOpenAi = analysisJob.isUseOpenAi();
        analysisJob.requeueFromDeadLetter();

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi, "admin-requeue");
    }

    @Transactional
    public AnalysisStatusResponse cancelAnalysis(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateOwnership(analysisJob, ownerId);

        if (analysisJob.isQueued()) {
            return cancelQueuedJob(analysisJob);
        }

        if (!analysisJob.isRunning()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_CANCEL_NOT_ALLOWED,
                    "진행 중이거나 대기 중인 분석 작업만 취소할 수 있습니다. status=" + analysisJob.getStatus()
            );
        }

        analysisJobStatusService.requestCancel(jobId);

        return analysisJobRepository.findByJobId(jobId)
                .map(AnalysisStatusResponse::from)
                .orElseGet(() -> AnalysisStatusResponse.from(analysisJob));
    }

    // 대기(QUEUED) 중인 작업은 아직 파이프라인이 시작되지 않았으므로, 실행 중 취소처럼
    // cancelRequested 플래그만 세우고 다음 체크포인트를 기다릴 필요가 없습니다. 그 자리에서
    // 바로 CANCELLED로 전이하고, 실행 중 취소와 동일한 후처리(진행률 캐시/결과 파일/메트릭)를
    // 맞춰 결과·진행률·목록 상태가 서로 어긋나지 않게 합니다.
    //
    // 이 찰나에 워커가 먼저 선점(claimForExecution)하면 @Version 낙관적 락 충돌로 감지되어,
    // "이미 실행 중이니 실행 중 취소를 다시 요청하라"고 안내합니다. 반대로 이 메서드가 먼저
    // CANCELLED로 커밋하면, 워커가 나중에 claimForExecution()을 호출해도 상태가 더 이상
    // QUEUED가 아니므로 선점에 실패해 조용히 건너뜁니다(중복 실행 없음).
    private AnalysisStatusResponse cancelQueuedJob(AnalysisJob analysisJob) {
        String jobId = analysisJob.getJobId();

        if (!analysisJob.cancelFromQueue()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_CANCEL_NOT_ALLOWED,
                    "취소를 시도하는 사이 작업 상태가 바뀌었습니다. 다시 시도해주세요. jobId=" + jobId
            );
        }

        try {
            analysisJobRepository.saveAndFlush(analysisJob);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "취소를 시도하는 사이 워커가 먼저 실행을 시작했습니다. 실행 중 취소를 다시 요청해주세요. jobId=" + jobId
            );
        }

        analysisProgressService.cancel(jobId, 0);
        saveCancelledResultSafely(jobId);
        meterRegistry.counter("analysis.job.cancelled").increment();
        log.info("[{}] 대기(QUEUED) 상태에서 즉시 취소되었습니다.", jobId);

        return AnalysisStatusResponse.from(analysisJob);
    }

    private AnalysisStatusResponse acceptAndDispatch(
            AnalysisJob analysisJob,
            boolean useVideoLlm,
            boolean useOpenAi,
            String trigger
    ) {
        // 배포 모드와 무관하게 항상 검사합니다. api/worker 분리 모드(dispatch.local-on-run=false)에서는
        // 아래 rejectIfExecutorSaturated()가 동작하지 않으므로, 워커가 느리거나 꺼져 있어도
        // DB에 QUEUED 작업이 무제한 쌓이지 않게 막는 방어선은 이 검사가 유일합니다.
        rejectIfQueueBackpressureExceeded(analysisJob.getOwnerId());

        // 로컬 즉시 투입 모드(monolith)에서만, 상태를 "시작됨"으로 바꾸기 전에 워커 스레드풀이
        // 이미 가득 찼는지 확인합니다. 여기서 막으면 job은 원래 상태로 남고 사용자에게는
        // 429(잠시 후 재시도)를 돌려줍니다. api/worker 분리 모드에서는 접수만 하고 워커 폴러가
        // 소비하므로 이 사전 점검은 하지 않습니다(대기 작업은 QUEUED로 안전하게 쌓입니다).
        if (localDispatchOnRun) {
            rejectIfExecutorSaturated();
        }

        meterRegistry.counter("analysis.job.started", "trigger", trigger).increment();

        String jobId = analysisJob.getJobId();

        // 실행을 "접수"만 하고 상태를 QUEUED로 둡니다. 실제 실행(BASIC_ANALYZING 전이)은
        // 백그라운드 워커가 claimForExecution()으로 선점할 때 일어납니다. 이렇게 하면 실행
        // 옵션이 DB에 남아, 재시작으로 워커 투입이 유실돼도 대기 작업을 이어서 실행할 수 있습니다.
        analysisJob.enqueue(useVideoLlm, useOpenAi);

        AnalysisJob savedJob;
        try {
            savedJob = analysisJobRepository.saveAndFlush(analysisJob);
        } catch (OptimisticLockingFailureException e) {
            // 같은 jobId로 거의 동시에 들어온 다른 실행 요청이 먼저 상태를 바꾼 경우입니다.
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "다른 요청이 먼저 분석을 시작했습니다. jobId=" + jobId
            );
        }

        // 지금 트랜잭션이 실제로 커밋된 뒤에만 백그라운드 작업을 시작합니다.
        // 커밋 전에 시작하면, 백그라운드 스레드가 아직 DB에 반영되지 않은
        // (다른 트랜잭션 기준으로는 보이지 않는) 상태를 참조할 수 있습니다.
        if (localDispatchOnRun) {
            // monolith 모드: 커밋 직후 이 인스턴스의 executor로 즉시 투입해 지연 없이 실행합니다.
            // 아직 claim 전(QUEUED)이므로, 파이프라인 시작 시 claimForExecution()을 거칩니다.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(jobId, useVideoLlm, useOpenAi, false);
                    }
                });
            } else {
                // 트랜잭션 없이 호출되는 경우(예: 테스트 코드)를 대비한 안전장치입니다.
                dispatch(jobId, useVideoLlm, useOpenAi, false);
            }
        } else {
            // api/worker 분리 모드: 접수(QUEUED)만 하고 즉시 투입하지 않습니다.
            // 별도의 worker 인스턴스에 있는 QueuedAnalysisJobPoller가 이 작업을 폴링해 실행합니다.
            log.info("[{}] 실행을 접수(QUEUED)했습니다. 워커 폴러가 곧 가져가 실행합니다.", jobId);
        }

        return AnalysisStatusResponse.from(savedJob);
    }

    // 사전 점검(rejectIfExecutorSaturated) 이후에도, 점검과 실제 제출 사이의 아주 짧은
    // 순간에 다른 요청이 대기열을 마저 채우면 여기서 RejectedExecutionException이 날 수
    // 있습니다. 이 경우 커밋은 이미 끝나 job이 "시작됨"으로 남아 있으므로, 곧바로 실패
    // 처리해 멈춘 것처럼 보이지 않게 하고 사용자가 바로 재시도할 수 있도록 합니다.
    //
    // alreadyClaimed=false: job이 아직 QUEUED라 파이프라인 시작 시 claimForExecution()으로
    // 선점을 시도해야 합니다(/run 즉시 투입, 재투입 스케줄러 재투입 모두 이 경로).
    // alreadyClaimed=true: 호출자가 claimNextQueuedJobs()로 이미 원자적으로 선점을 마쳐
    // DB 상태가 이미 BASIC_ANALYZING이므로, 파이프라인은 claimForExecution()을 건너뜁니다.
    // 만약 이 제출 자체가 거부되면(RejectedExecutionException), 이미 선점된 작업이 DB에
    // BASIC_ANALYZING으로 남아 아무도 다시 집어가지 못하는 "유령 작업"이 되지 않도록
    // handleDispatchRejected()가 즉시 실패 처리합니다. 이 처리마저 누락되는 극히 드문 경우는
    // StuckAnalysisJobWatchdogService(멈춘 작업 워치도그)가 max-running-minutes 뒤에
    // 자동으로 실패 처리하는 안전망 역할을 합니다.
    private void dispatch(String jobId, boolean useVideoLlm, boolean useOpenAi, boolean alreadyClaimed) {
        try {
            analysisTaskExecutor.execute(
                    () -> executeAnalysisAsync(jobId, useVideoLlm, useOpenAi, alreadyClaimed)
            );
        } catch (RejectedExecutionException e) {
            handleDispatchRejected(jobId, e);
        }
    }

    // 재시작 등으로 워커 투입이 유실된 QUEUED 작업을 다시 워커 풀에 투입합니다.
    // 실제 실행 여부는 워커가 claimForExecution()으로 판단하므로 중복 투입돼도 안전하고,
    // 풀이 가득 차 제출이 거부되면 dispatch()가 그 작업을 실패 처리합니다.
    public void redispatchQueuedJob(String jobId, boolean useVideoLlm, boolean useOpenAi) {
        dispatch(jobId, useVideoLlm, useOpenAi, false);
    }

    // QueuedAnalysisJobPoller가 AnalysisJobStatusService.claimNextQueuedJobs()로 이미
    // 원자적으로 선점(claim)한 작업을 실행 제출합니다. "조회 후 실행 제출" 방식과 달리, 이
    // 메서드가 호출된 시점에 이미 이 인스턴스가 실행 소유권을 확정한 상태이므로, 여러 워커가
    // 같은 후보를 반복 조회/제출했다가 뒤늦게 선점 실패를 발견하는 낭비가 없습니다.
    public void dispatchClaimedJob(String jobId, boolean useVideoLlm, boolean useOpenAi) {
        dispatch(jobId, useVideoLlm, useOpenAi, true);
    }

    private void handleDispatchRejected(String jobId, RejectedExecutionException e) {
        String failReason =
                "분석 워커 대기열이 가득 차 작업을 시작하지 못했습니다. 잠시 후 다시 시도해주세요.";
        log.warn("[{}] 백그라운드 분석 작업 제출이 거부되어 실패 처리합니다.", jobId, e);
        analysisJobStatusService.failStatus(jobId, failReason);
        analysisProgressService.fail(jobId, 0, failReason);
        saveFailureResultSafely(jobId, failReason);
        meterRegistry.counter("analysis.job.failed", "reason", "queue-full").increment();
    }

    // DB에 쌓인 QUEUED 작업 수가 설정된 한도를 넘었는지 확인합니다. 전역 한도를 먼저 검사해
    // 시스템 전체가 과부하 상태인지 보고, 그다음 사용자별 한도로 한 사용자가 대기열을 독점하지
    // 못하게 막습니다. 두 검사 모두 "최선 노력(best-effort)"이라 아주 짧은 경합 구간에서는
    // 한도를 살짝 넘길 수 있지만, 워커가 죽어도 무한정 쌓이는 것을 막기에는 충분합니다.
    private void rejectIfQueueBackpressureExceeded(Long ownerId) {
        long globalQueuedCount = analysisJobRepository.countByStatus(AnalysisStatus.QUEUED);

        if (globalQueuedCount >= analysisQueueProperties.maxGlobalQueued()) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-global").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "현재 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."
            );
        }

        long ownerQueuedCount = analysisJobRepository.countByStatusAndOwnerId(AnalysisStatus.QUEUED, ownerId);

        if (ownerQueuedCount >= analysisQueueProperties.maxQueuedPerUser()) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-per-user").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "대기 중인 분석 작업이 너무 많습니다. 이전 작업이 끝난 뒤 다시 시도해주세요. (최대 "
                            + analysisQueueProperties.maxQueuedPerUser() + "건)"
            );
        }
    }

    // 워커 스레드 풀과 대기열이 모두 가득 찬 상태인지 확인합니다. 가득 찼다면 새 작업을
    // 받지 않고 429로 안내합니다. 이 점검은 "최선 노력(best-effort)"이라 아주 짧은 경합
    // 구간은 남지만, 그 경합은 dispatch()의 RejectedExecutionException 처리로 보완됩니다.
    private void rejectIfExecutorSaturated() {
        ThreadPoolExecutor executor;
        try {
            executor = analysisTaskExecutor.getThreadPoolExecutor();
        } catch (IllegalStateException notInitialized) {
            // 아직 풀이 초기화되지 않았거나 사용할 수 없는 상태면 사전 점검을 건너뜁니다.
            return;
        }

        if (executor == null) {
            return;
        }

        boolean saturated = executor.getQueue().remainingCapacity() == 0
                && executor.getActiveCount() >= executor.getMaximumPoolSize();

        if (saturated) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "현재 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."
            );
        }
    }

    // 백그라운드 스레드에서 실행되는 실제 분석 파이프라인입니다.
    // 이 메서드 자체에는 @Transactional을 걸지 않습니다. 각 단계는
    // analysisJobStatusService / resultCommandService 안에서 그때그때 커밋되므로,
    // 여기서 실패해도 이미 커밋된 중간 결과는 그대로 남습니다.
    private void executeAnalysisAsync(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi,
            boolean alreadyClaimed
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        MDC.put("jobId", jobId);

        try {
            runAnalysisPipeline(jobId, useVideoLlm, useOpenAi, sample, alreadyClaimed);
        } finally {
            // 조기 return/예외/취소 등 어떤 경로로 끝나든, 워커 스레드 재사용 시
            // 이전 작업의 jobId가 다음 작업 로그에 남지 않도록 반드시 정리합니다.
            MDC.remove("jobId");
        }
    }

    // 실제 분석 파이프라인 본문입니다. 기존 로직/메트릭 타이머 호출 위치는 그대로이며,
    // MDC(jobId) 정리는 executeAnalysisAsync의 finally가 모든 종료 경로를 보장합니다.
    private void runAnalysisPipeline(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi,
            Timer.Sample sample,
            boolean alreadyClaimed
    ) {
        // alreadyClaimed=true면 호출자(QueuedAnalysisJobPoller)가 claimNextQueuedJobs()로
        // 조회와 동시에 이미 원자적으로 선점(QUEUED -> BASIC_ANALYZING)을 마친 상태입니다.
        // 그렇지 않은 경로(/run 즉시 투입, 재투입 스케줄러)는 아직 QUEUED이므로 여기서
        // claimForExecution()으로 선점을 시도합니다. 재시작 복구로 같은 작업이 두 번
        // 투입되면, 먼저 선점한 워커만 진행하고 나머지는 조용히 종료해 중복 실행을 막습니다.
        if (!alreadyClaimed && !analysisJobStatusService.claimForExecution(jobId)) {
            log.info("[{}] 실행 선점에 실패해(이미 다른 워커가 처리 중이거나 대기 상태가 아님) 이 워커는 종료합니다.", jobId);
            stopDurationTimer(sample, "skipped");
            return;
        }

        UploadedVideo uploadedVideo;

        try {
            uploadedVideo = uploadedVideoRepository.findByJobId(jobId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.FILE_NOT_FOUND,
                            "업로드된 영상 정보를 찾을 수 없습니다."
                    ));
        } catch (Exception e) {
            log.error("[{}] 업로드 영상 정보를 찾지 못해 분석을 시작할 수 없습니다.", jobId, e);
            String failReason = "업로드된 영상 정보를 찾을 수 없습니다.";
            analysisJobStatusService.failStatus(jobId, failReason);
            analysisProgressService.fail(jobId, 0, failReason);
            saveFailureResultSafely(jobId, failReason);
            meterRegistry.counter("analysis.job.failed", "reason", "upload-not-found").increment();
            stopDurationTimer(sample, "failed");
            return;
        }

        log.info("[{}] 분석 파이프라인 시작 (useVideoLlm={}, useOpenAi={})", jobId, useVideoLlm, useOpenAi);
        analysisProgressService.start(jobId);

        // 이 작업의 마감 시각(deadline). 각 단계 사이 체크포인트에서 이 시각을 넘겼는지 확인해
        // 초과 시 자동 실패시킵니다.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(jobTimeoutMinutes));

        int lastPercent = 10;

        try {
            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            log.info("[{}] ({}%) 기본 분석 요청을 analysis-engine으로 전송합니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.BASIC_ANALYSIS, AnalysisStatus.BASIC_ANALYZING,
                    lastPercent, "영상/음성 기본 분석을 실행하는 중입니다."
            );

            String videoDownloadUrl = videoFileCommandService.resolveDownloadUrl(jobId, uploadedVideo.getStoredFilePath());

            AnalysisEngineResponse analysisEngineResponse = analysisEngineClient.analyze(
                    new AnalysisEngineRequest(
                            jobId,
                            uploadedVideo.getStoredFilePath(),
                            videoDownloadUrl
                    )
            );
            log.info("[{}] 기본 분석 응답을 받았습니다.", jobId);

            VideoLlmEngineResponse videoLlmEngineResponse;

            if (!useVideoLlm) {
                log.info("[{}] Video LLM 분석을 건너뜁니다. (useVideoLlm=false)", jobId);
                videoLlmEngineResponse = createSkippedVideoLlmResponse(jobId, VideoLlmSkipReason.DISABLED);
            } else if (!canUseMonthlyVideoLlmBudget(jobId)) {
                log.info("[{}] Video LLM 분석을 건너뜁니다. (월간 한도 초과)", jobId);
                videoLlmEngineResponse = createSkippedVideoLlmResponse(jobId, VideoLlmSkipReason.MONTHLY_BUDGET_EXCEEDED);
            } else {
                if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                    return;
                }

                analysisJobStatusService.updateStatus(jobId, AnalysisStatus.VIDEO_LLM_ANALYZING);
                lastPercent = 40;
                log.info("[{}] ({}%) Video LLM 분석 요청을 전송합니다.", jobId, lastPercent);
                analysisProgressService.update(
                        jobId, AnalysisStep.VIDEO_LLM_ANALYSIS, AnalysisStatus.VIDEO_LLM_ANALYZING,
                        lastPercent, "Video LLM 분석을 실행하는 중입니다."
                );

                videoLlmEngineResponse = videoLlmEngineClient.analyze(
                        VideoLlmEngineRequest.defaultOption(
                                jobId,
                                uploadedVideo.getStoredFilePath(),
                                resolveDurationSec(jobId, uploadedVideo.getStoredFilePath()),
                                videoDownloadUrl
                        )
                );
                log.info("[{}] Video LLM 분석 응답을 받았습니다.", jobId);
            }

            analysisJobStatusService.updateStatus(jobId, AnalysisStatus.COMPACTING);
            lastPercent = 60;
            log.info("[{}] ({}%) 분석 결과를 정리(compact)하는 중입니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.COMPACT_ANALYSIS, AnalysisStatus.COMPACTING,
                    lastPercent, "분석 결과를 정리하는 중입니다."
            );

            Map<String, Object> compactAnalysis = resultCommandService.saveEngineResultsAndCompact(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse
            );

            OpenAiFeedbackResponse openAiFeedbackResponse;

            if (useOpenAi) {
                if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                    return;
                }

                analysisJobStatusService.updateStatus(jobId, AnalysisStatus.OPENAI_GENERATING);
                lastPercent = 75;
                log.info("[{}] ({}%) AI 피드백을 생성하는 중입니다.", jobId, lastPercent);
                analysisProgressService.update(
                        jobId, AnalysisStep.OPENAI_FEEDBACK, AnalysisStatus.OPENAI_GENERATING,
                        lastPercent, "AI 피드백을 생성하는 중입니다."
                );

                openAiFeedbackResponse = resultCommandService.loadExistingRealOpenAiFeedback(jobId)
                        .map(existingFeedback -> {
                            log.info(
                                    "OPENAI_REUSE jobId={} 이전에 성공한 실제 OpenAI 응답을 재사용합니다. (재호출 생략)",
                                    jobId
                            );
                            return existingFeedback;
                        })
                        .orElseGet(() -> openAiClient.generateFeedback(
                                new OpenAiFeedbackRequest(jobId, compactAnalysis)
                        ));
                log.info("[{}] AI 피드백 생성이 끝났습니다. (mode={})", jobId, openAiFeedbackResponse.generationMode());
            } else {
                log.info("[{}] OpenAI 피드백 생성을 건너뜁니다. (useOpenAi=false)", jobId);
                openAiFeedbackResponse = createSkippedOpenAiResponse(jobId);
            }

            resultCommandService.saveOpenAiFeedbackResult(
                    jobId,
                    openAiFeedbackResponse
            );

            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            analysisJobStatusService.updateStatus(jobId, AnalysisStatus.MERGING_RESULT);
            lastPercent = 90;
            log.info("[{}] ({}%) 최종 결과를 병합하는 중입니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.RESULT_MERGE, AnalysisStatus.MERGING_RESULT,
                    lastPercent, "최종 결과를 병합하는 중입니다."
            );

            resultCommandService.saveFinalResult(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse,
                    openAiFeedbackResponse
            );

            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            analysisJobStatusService.completeStatus(jobId);
            meterRegistry.counter("analysis.job.completed").increment();
            stopDurationTimer(sample, "completed");
            analysisProgressService.complete(jobId);
            log.info("[{}] (100%) 분석 파이프라인이 완료되었습니다.", jobId);
        } catch (BusinessException e) {
            log.warn("[{}] 분석이 실패했습니다: {}", jobId, e.getMessage());
            analysisJobStatusService.failStatus(jobId, e.getMessage());
            analysisProgressService.fail(jobId, lastPercent, e.getMessage());
            saveFailureResultSafely(jobId, e.getMessage());
            meterRegistry.counter("analysis.job.failed", "reason", "business").increment();
            stopDurationTimer(sample, "failed");
        } catch (Exception e) {
            String failReason = e.getMessage() == null
                    ? "분석 실행 중 알 수 없는 오류가 발생했습니다."
                    : e.getMessage();

            log.error("[{}] 분석 중 예상하지 못한 오류가 발생했습니다.", jobId, e);
            analysisJobStatusService.failStatus(jobId, failReason);
            analysisProgressService.fail(jobId, lastPercent, failReason);
            saveFailureResultSafely(jobId, failReason);
            meterRegistry.counter("analysis.job.failed", "reason", "unexpected").increment();
            stopDurationTimer(sample, "failed");
        }
    }

    private Double resolveDurationSec(String jobId, String storedFilePath) {
        try {
            Optional<Duration> duration = videoDurationProbe.probe(Path.of(storedFilePath));
            return duration
                    .map(value -> value.toMillis() / 1000.0)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[{}] Video LLM durationSec 계산에 실패해 null로 전송합니다. path={}", jobId, storedFilePath, e);
            return null;
        }
    }

    private boolean canUseMonthlyVideoLlmBudget(String jobId) {
        boolean allowed = userRateLimiter.tryConsume("video-llm-monthly", currentMonthKey());
        if (!allowed) {
            log.warn("[{}] Video LLM 월간 호출 한도를 초과해 실제 호출을 생략합니다.", jobId);
        }
        return allowed;
    }

    private String currentMonthKey() {
        return YearMonth.now().toString();
    }

    // 각 단계 사이에서 호출됩니다. (1) 마감 시각 초과면 timeout으로, (2) 취소 요청이 있으면
    // cancelled로 남은 단계를 중단합니다. 둘 중 하나라도 해당하면 true를 반환합니다.
    // 타임아웃을 먼저 확인합니다(예산을 넘긴 작업은 취소 여부와 무관하게 종료해야 하므로).
    private boolean stopIfCancelledOrTimedOut(
            String jobId,
            int lastPercent,
            Timer.Sample sample,
            Instant deadline
    ) {
        if (Instant.now().isAfter(deadline)) {
            String failReason = "분석이 제한 시간(" + jobTimeoutMinutes + "분)을 초과해 자동으로 종료되었습니다.";
            log.warn("[{}] {}", jobId, failReason);
            analysisJobStatusService.failStatus(jobId, failReason);
            analysisProgressService.fail(jobId, lastPercent, failReason);
            saveFailureResultSafely(jobId, failReason);
            meterRegistry.counter("analysis.job.failed", "reason", "timeout").increment();
            stopDurationTimer(sample, "timeout");
            return true;
        }

        boolean cancelRequested = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::isCancelRequested)
                .orElse(false);

        if (!cancelRequested) {
            return false;
        }

        log.info("[{}] 취소 요청을 감지해 남은 분석 단계를 중단합니다.", jobId);
        analysisJobStatusService.cancelStatus(jobId);
        analysisProgressService.cancel(jobId, lastPercent);
        saveCancelledResultSafely(jobId);
        meterRegistry.counter("analysis.job.cancelled").increment();
        stopDurationTimer(sample, "cancelled");
        return true;
    }

    private void stopDurationTimer(Timer.Sample sample, String outcome) {
        sample.stop(
                Timer.builder("analysis.job.duration")
                        .tag("outcome", outcome)
                        .register(meterRegistry)
        );
    }

    private void validateRunnable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning() || analysisJob.isQueued()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "이미 실행이 접수되었거나 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRun()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "현재 상태에서는 분석을 실행할 수 없습니다. status=" + analysisJob.getStatus()
            );
        }
    }

    private void validateRetryable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning() || analysisJob.isQueued()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "이미 실행이 접수되었거나 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        // DEAD_LETTER는 canRetry()에서 이미 제외되지만, 아래의 일반 "실패/취소 상태만
        // 재시도 가능" 메시지 대신 기존과 동일한 ANALYSIS_RETRY_LIMIT_EXCEEDED(409)를
        // 반환합니다. DEAD_LETTER는 정의상 재시도 한도를 소진한 상태이므로, 사용자에게는
        // 이 API 계약을 유지하면서 관리자 재처리가 필요하다는 것만 안내하면 충분합니다.
        if (analysisJob.isDeadLetter()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED,
                    "재시도 가능 횟수를 모두 소진했습니다. 관리자 재처리가 필요합니다. jobId="
                            + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRetry()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "실패 또는 취소 상태의 분석 작업만 재시도할 수 있습니다. status=" + analysisJob.getStatus()
            );
        }

        if (analysisJob.getRetryCount() >= analysisRetryProperties.maxCount()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED,
                    "재시도 가능 횟수를 초과했습니다. jobId=" + analysisJob.getJobId()
                            + ", retryCount=" + analysisJob.getRetryCount()
                            + ", maxRetryCount=" + analysisRetryProperties.maxCount()
            );
        }
    }

    private void validateOwnership(AnalysisJob analysisJob, Long ownerId) {
        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }
    }

    private void saveFailureResultSafely(
            String jobId,
            String failReason
    ) {
        try {
            resultCommandService.saveFailureResult(
                    jobId,
                    AnalysisStatus.FAILED.name(),
                    failReason
            );
        } catch (Exception ignored) {
            // 실패 결과 저장 중 발생한 예외는 원래 분석 실패 원인을 덮어쓰지 않기 위해 무시합니다.
        }
    }

    private void saveCancelledResultSafely(String jobId) {
        try {
            resultCommandService.saveFailureResult(
                    jobId,
                    AnalysisStatus.CANCELLED.name(),
                    "사용자 요청으로 분석 작업이 취소되었습니다."
            );
        } catch (Exception ignored) {
            // 취소 결과 저장 실패가 취소 상태 반영을 덮어쓰지 않도록 무시합니다.
        }
    }

    private VideoLlmEngineResponse createSkippedVideoLlmResponse(String jobId, VideoLlmSkipReason reason) {
        return new VideoLlmEngineResponse(
                jobId,
                "skipped",
                Map.of(
                        "name", "video-llm-skipped",
                        "version", "none",
                        "generationMode", "SKIPPED"
                ),
                Map.of(),
                Map.of(
                        "visualDelivery", reason.visualDelivery,
                        "mainStrength", reason.mainStrength,
                        "mainWeakness", reason.mainWeakness
                )
        );
    }

    private OpenAiFeedbackResponse createSkippedOpenAiResponse(String jobId) {
        return new OpenAiFeedbackResponse(
                jobId,
                "OpenAI 피드백 생성을 사용하지 않았습니다. 기본 분석 결과만 저장되었습니다.",
                List.of(
                        "기본 분석 결과가 정상적으로 생성되었습니다."
                ),
                List.of(
                        "OpenAI 피드백을 활성화하면 더 구체적인 개선점을 받을 수 있습니다."
                ),
                List.of(),
                List.of()
        );
    }
}
