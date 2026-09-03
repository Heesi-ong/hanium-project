package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.PracticeGoal;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.CoachingProfile;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AnalysisCommandService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCommandService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final UserRepository userRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoFileCommandService videoFileCommandService;
    private final JobIdGenerator jobIdGenerator;
    private final AnalysisProgressService analysisProgressService;
    private final AnalysisJobStatusService analysisJobStatusService;
    private final ThreadPoolTaskExecutor analysisTaskExecutor;
    private final AnalysisJobValidator analysisJobValidator;
    private final AnalysisDispatchAdmissionPolicy dispatchAdmissionPolicy;
    private final AnalysisRetryPolicy retryPolicy;
    private final AnalysisPipelineTerminationHandler pipelineTerminationHandler;
    private final AnalysisOpenAiFeedbackStage openAiFeedbackStage;
    private final AnalysisVideoLlmStage videoLlmStage;
    private final AnalysisBasicStage basicStage;
    private final AnalysisResultPersistenceStage resultPersistenceStage;
    private final AnalysisPipelineOutcomeHandler pipelineOutcomeHandler;
    private final AnalysisPipelineStageReporter pipelineStageReporter;

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

    // video-llm-engine과 같은 청크 길이를 사용해 작업 1건이 실제로 만들 NVIDIA 호출 수를
    // 월간 예산에서 예약한다. 영상 길이를 다시 확인하지 못하면 업로드 허용 최대 길이로
    // 보수적으로 계산해, 비용 가드가 실제 호출 수보다 작아지는 fail-open을 막는다.
    @Value("${video-llm.budget.chunk-duration-seconds:100}")
    private double videoLlmChunkDurationSeconds = 100;

    @Value("${video.max-duration-minutes:30}")
    private long videoMaxDurationMinutes = 30;

    private final MeterRegistry meterRegistry;

    public AnalysisCommandService(
            AnalysisJobRepository analysisJobRepository,
            UserRepository userRepository,
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
            AnalysisQueueProperties analysisQueueProperties,
            MeterRegistry meterRegistry,
            AnalysisJobValidator analysisJobValidator
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.userRepository = userRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoFileCommandService = videoFileCommandService;
        this.jobIdGenerator = jobIdGenerator;
        this.analysisProgressService = analysisProgressService;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.meterRegistry = meterRegistry;
        this.analysisJobValidator = analysisJobValidator;
        this.dispatchAdmissionPolicy = new AnalysisDispatchAdmissionPolicy(
                analysisJobRepository,
                analysisTaskExecutor,
                analysisQueueProperties,
                meterRegistry
        );
        this.retryPolicy = new AnalysisRetryPolicy();
        this.pipelineTerminationHandler = new AnalysisPipelineTerminationHandler(
                analysisJobRepository,
                analysisJobStatusService,
                analysisProgressService,
                resultCommandService,
                meterRegistry
        );
        this.openAiFeedbackStage = new AnalysisOpenAiFeedbackStage(
                resultCommandService,
                openAiClient
        );
        this.videoLlmStage = new AnalysisVideoLlmStage(
                analysisJobRepository,
                userRateLimiter,
                videoDurationProbe,
                videoLlmEngineClient
        );
        this.basicStage = new AnalysisBasicStage(
                videoFileCommandService,
                analysisEngineClient
        );
        this.resultPersistenceStage = new AnalysisResultPersistenceStage(resultCommandService);
        this.pipelineOutcomeHandler = new AnalysisPipelineOutcomeHandler(
                analysisJobStatusService,
                analysisProgressService,
                resultPersistenceStage,
                meterRegistry
        );
        this.pipelineStageReporter = new AnalysisPipelineStageReporter(
                analysisJobStatusService,
                analysisProgressService
        );
    }

    @Transactional
    public AnalysisUploadResponse uploadVideo(MultipartFile file, Long ownerId) {
        return uploadVideo(file, ownerId, null, null);
    }

    @Transactional
    public AnalysisUploadResponse uploadVideo(
            MultipartFile file,
            Long ownerId,
            String baselineJobId,
            PracticeGoal practiceGoal
    ) {
        String jobId = jobIdGenerator.generate();

        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        if ((baselineJobId == null) != (practiceGoal == null)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "기준 분석과 연습 목표는 함께 지정해야 합니다."
            );
        }
        if (baselineJobId != null) {
            AnalysisJob baselineJob = analysisJobRepository.findByJobId(baselineJobId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));
            if (!ownerId.equals(baselineJob.getOwnerId())) {
                throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
            }
            if (!baselineJob.isCompleted()) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "완료된 분석만 재연습 기준으로 사용할 수 있습니다."
                );
            }
            analysisJob.linkPracticeBaseline(baselineJob, practiceGoal);
        }
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

        UploadedVideo savedVideo = uploadedVideoRepository.save(uploadedVideo);
        savedJob.linkVideoAsset(savedVideo.getId());

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

        analysisJobValidator.validateOwnership(analysisJob, ownerId);
        analysisJobValidator.validateRunnable(analysisJob);

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi, "run");
    }

    @Transactional
    public AnalysisStatusResponse retryAnalysis(
            String jobId,
            Long ownerId
    ) {
        return retryAnalysisInternal(jobId, ownerId, null, null);
    }

    @Transactional
    public AnalysisStatusResponse retryAnalysis(
            String jobId,
            Long ownerId,
            Boolean useVideoLlm,
            Boolean useOpenAi
    ) {
        return retryAnalysisInternal(jobId, ownerId, useVideoLlm, useOpenAi);
    }

    private AnalysisStatusResponse retryAnalysisInternal(
            String jobId,
            Long ownerId,
            Boolean useVideoLlmOverride,
            Boolean useOpenAiOverride
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        analysisJobValidator.validateOwnership(analysisJob, ownerId);
        analysisJobValidator.validateRetryable(analysisJob);

        // 일반적인 "재시도"는 실패한 작업을 같은 조건으로 다시 실행해야 합니다. 요청 본문이
        // 생략됐는데 true/true를 기본값으로 쓰면, 사용자가 최초 실행에서 끈 외부 AI 전송과
        // 비용 발생 옵션이 재시도 순간 다시 켜질 수 있습니다. 명시적 override가 있을 때만
        // 옵션을 바꾸고, 기본 경로에서는 DB에 저장된 최초 선택을 그대로 보존합니다.
        AnalysisRetryPolicy.RetryOptions retryOptions = retryPolicy.resolve(
                analysisJob,
                useVideoLlmOverride,
                useOpenAiOverride
        );

        analysisJob.resetForRetry();

        return acceptAndDispatch(
                analysisJob,
                retryOptions.useVideoLlm(),
                retryOptions.useOpenAi(),
                "retry"
        );
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

        analysisJobValidator.validateOwnership(analysisJob, ownerId);

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
        resultPersistenceStage.saveCancelledSafely(jobId);
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
        dispatchAdmissionPolicy.verify(analysisJob.getOwnerId(), localDispatchOnRun);

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

    AnalysisStatusResponse acceptVideoLlmReanalysis(
            AnalysisJob reanalysisJob,
            boolean useOpenAi
    ) {
        if (reanalysisJob.getAnalysisKind() != AnalysisKind.VIDEO_LLM_REANALYSIS
                || reanalysisJob.getSourceJobId() == null
                || reanalysisJob.getVideoAssetId() == null) {
            throw new IllegalArgumentException("유효한 Video LLM 재분석 child job이 아닙니다.");
        }

        return acceptAndDispatch(
                reanalysisJob,
                true,
                useOpenAi,
                "video-llm-reanalysis"
        );
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
        pipelineOutcomeHandler.failBeforeExecution(
                jobId,
                0,
                failReason,
                "queue-full"
        );
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
            pipelineOutcomeHandler.stopSkipped(sample);
            return;
        }

        UploadedVideo uploadedVideo;

        try {
            uploadedVideo = findVideoAsset(jobId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.FILE_NOT_FOUND,
                            "업로드된 영상 정보를 찾을 수 없습니다."
                    ));
        } catch (Exception e) {
            log.error("[{}] 업로드 영상 정보를 찾지 못해 분석을 시작할 수 없습니다.", jobId, e);
            String failReason = "업로드된 영상 정보를 찾을 수 없습니다.";
            pipelineOutcomeHandler.fail(
                    jobId,
                    0,
                    failReason,
                    "upload-not-found",
                    sample
            );
            return;
        }

        log.info("[{}] 분석 파이프라인 시작 (useVideoLlm={}, useOpenAi={})", jobId, useVideoLlm, useOpenAi);
        pipelineStageReporter.start(jobId);

        // 이 작업의 마감 시각(deadline). 각 단계 사이 체크포인트에서 이 시각을 넘겼는지 확인해
        // 초과 시 자동 실패시킵니다.
        Instant deadline = Instant.now().plus(Duration.ofMinutes(jobTimeoutMinutes));

        int lastPercent = 10;

        try {
            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            lastPercent = pipelineStageReporter.beginBasicAnalysis(jobId);

            AnalysisBasicStage.Result basicResult = basicStage.analyze(
                    jobId,
                    uploadedVideo.getJobId(),
                    uploadedVideo.getStoredFilePath()
            );
            AnalysisEngineResponse analysisEngineResponse = basicResult.response();
            String videoDownloadUrl = basicResult.videoDownloadUrl();

            // 스켈레톤 오버레이 프레임 저장은 사용자에게 "이렇게 분석했다"를 보여주기 위한
            // 부가 기능이므로, 저장에 실패하더라도 분석 파이프라인은 계속 진행합니다.
            try {
                analysisEngineResponse = resultPersistenceStage.persistFrameOverlays(
                        jobId,
                        analysisEngineResponse
                );
            } catch (Exception e) {
                log.warn("[{}] 오버레이 프레임 저장에 실패해 갤러리 없이 계속 진행합니다: {}", jobId, e.getMessage());
            }

            AnalysisVideoLlmStage.Plan videoLlmPlan = videoLlmStage.prepare(
                    jobId,
                    useVideoLlm,
                    uploadedVideo.getStoredFilePath(),
                    videoLlmChunkDurationSeconds,
                    videoMaxDurationMinutes
            );
            VideoLlmEngineResponse videoLlmEngineResponse;

            if (videoLlmPlan.skipped()) {
                videoLlmEngineResponse = videoLlmPlan.skippedResponse();
            } else {
                if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                    return;
                }

                lastPercent = pipelineStageReporter.beginVideoLlmAnalysis(jobId);

                videoLlmEngineResponse = videoLlmStage.analyze(
                        jobId,
                        uploadedVideo.getStoredFilePath(),
                        videoDownloadUrl,
                        videoLlmPlan
                );
            }

            lastPercent = pipelineStageReporter.beginCompacting(jobId);

            Map<String, Object> compactAnalysis = resultPersistenceStage.compact(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse
            );

            if (useOpenAi) {
                if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                    return;
                }

                lastPercent = pipelineStageReporter.beginOpenAiFeedback(jobId);
            }

            OpenAiFeedbackResponse openAiFeedbackResponse = openAiFeedbackStage.generateAndSave(
                    jobId,
                    useOpenAi,
                    compactAnalysis,
                    resolveCoachingProfile(jobId)
            );

            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            lastPercent = pipelineStageReporter.beginResultMerge(jobId);

            resultPersistenceStage.saveFinal(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse,
                    openAiFeedbackResponse
            );

            if (stopIfCancelledOrTimedOut(jobId, lastPercent, sample, deadline)) {
                return;
            }

            pipelineOutcomeHandler.complete(
                    jobId,
                    AnalysisVideoLlmStage.resolveGenerationMode(videoLlmEngineResponse),
                    sample
            );
        } catch (BusinessException e) {
            log.warn("[{}] 분석이 실패했습니다: {}", jobId, e.getMessage());
            pipelineOutcomeHandler.fail(
                    jobId,
                    lastPercent,
                    e.getMessage(),
                    "business",
                    sample
            );
        } catch (Exception e) {
            String failReason = e.getMessage() == null
                    ? "분석 실행 중 알 수 없는 오류가 발생했습니다."
                    : e.getMessage();

            log.error("[{}] 분석 중 예상하지 못한 오류가 발생했습니다.", jobId, e);
            pipelineOutcomeHandler.fail(
                    jobId,
                    lastPercent,
                    failReason,
                    "unexpected",
                    sample
            );
        }
    }

    private CoachingProfile resolveCoachingProfile(String jobId) {
        return analysisJobRepository.findByJobId(jobId)
                .flatMap(job -> userRepository.findById(job.getOwnerId()))
                .map(user -> CoachingProfile.of(
                        user.getPurpose(),
                        user.getExperienceLevel(),
                        user.getImprovementGoal()
                ))
                .orElseGet(CoachingProfile::empty);
    }

    private Optional<UploadedVideo> findVideoAsset(String jobId) {
        Optional<UploadedVideo> linkedAsset = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::getVideoAssetId)
                .flatMap(uploadedVideoRepository::findById);

        return linkedAsset.or(() -> uploadedVideoRepository.findByJobId(jobId));
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
        return pipelineTerminationHandler.stopIfCancelledOrTimedOut(
                jobId,
                lastPercent,
                sample,
                deadline,
                jobTimeoutMinutes
        );
    }

}
