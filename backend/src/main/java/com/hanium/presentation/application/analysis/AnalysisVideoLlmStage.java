package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

/**
 * Video LLM 단계의 실행 계획과 provider 응답 정책을 담당합니다.
 *
 * <p>파이프라인 상태/진행률과 취소 체크포인트는 호출자가 유지하고, 이 클래스는 duration,
 * 비용 예약, 명시적 생략, 재분석 REAL 강제 계약에 집중합니다.</p>
 */
final class AnalysisVideoLlmStage {

    private static final Logger log = LoggerFactory.getLogger(AnalysisVideoLlmStage.class);

    private enum SkipReason {
        DISABLED(
                "이 작업은 Video LLM 분석 없이 진행하도록 설정되었습니다.",
                "Video LLM 분석 비활성화",
                "사용자 설정으로 Video LLM 분석이 생략되었습니다."
        ),
        MONTHLY_BUDGET_EXCEEDED(
                "이번 달 Video LLM 분석 호출 한도를 초과해 이 작업은 Video LLM 분석 없이 처리되었습니다.",
                "Video LLM 월간 한도 초과",
                "월간 호출 한도 초과로 Video LLM 분석이 생략되었습니다."
        ),
        DAILY_LIMIT_EXCEEDED(
                "오늘 계정에서 사용할 수 있는 Video LLM 분석 호출 한도를 초과해 이 작업은 Video LLM 분석 없이 처리되었습니다.",
                "Video LLM 일일 한도 초과",
                "사용자별 일일 호출 한도 초과로 Video LLM 분석이 생략되었습니다."
        );

        private final String visualDelivery;
        private final String mainStrength;
        private final String mainWeakness;

        SkipReason(String visualDelivery, String mainStrength, String mainWeakness) {
            this.visualDelivery = visualDelivery;
            this.mainStrength = mainStrength;
            this.mainWeakness = mainWeakness;
        }
    }

    record Plan(
            Double durationSec,
            boolean requireReal,
            VideoLlmEngineResponse skippedResponse
    ) {
        boolean skipped() {
            return skippedResponse != null;
        }
    }

    private final AnalysisJobRepository analysisJobRepository;
    private final UserRateLimiter userRateLimiter;
    private final VideoDurationProbe videoDurationProbe;
    private final VideoLlmEngineClient videoLlmEngineClient;
    private final Clock clock;

    AnalysisVideoLlmStage(
            AnalysisJobRepository analysisJobRepository,
            UserRateLimiter userRateLimiter,
            VideoDurationProbe videoDurationProbe,
            VideoLlmEngineClient videoLlmEngineClient
    ) {
        this(
                analysisJobRepository,
                userRateLimiter,
                videoDurationProbe,
                videoLlmEngineClient,
                Clock.systemDefaultZone()
        );
    }

    AnalysisVideoLlmStage(
            AnalysisJobRepository analysisJobRepository,
            UserRateLimiter userRateLimiter,
            VideoDurationProbe videoDurationProbe,
            VideoLlmEngineClient videoLlmEngineClient,
            Clock clock
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.userRateLimiter = userRateLimiter;
        this.videoDurationProbe = videoDurationProbe;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.clock = clock;
    }

    // durationSec 계산과 재분석 여부 판정만 한다. 예산 예약(월간/일일 NVIDIA units)은
    // 부수효과이므로 prepare()가 아니라 reserveBudgetOrSkip()에서 실제 호출 직전에 한다.
    Plan prepare(
            String jobId,
            boolean useVideoLlm,
            String storedFilePath
    ) {
        boolean requireReal = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::getAnalysisKind)
                .filter(AnalysisKind.VIDEO_LLM_REANALYSIS::equals)
                .isPresent();

        if (!useVideoLlm) {
            if (requireReal) {
                throw new BusinessException(
                        ErrorCode.VIDEO_LLM_REAL_REQUIRED,
                        "실제 Video LLM 재분석을 실행할 수 없습니다. reason=" + SkipReason.DISABLED
                );
            }
            log.info("[{}] Video LLM 분석을 건너뜁니다. ({})", jobId, SkipReason.DISABLED);
            return new Plan(null, requireReal, createSkippedResponse(jobId, SkipReason.DISABLED));
        }

        return new Plan(resolveDurationSec(jobId, storedFilePath), requireReal, null);
    }

    /**
     * 월간/일일 NVIDIA 호출 예산을 실제 엔진 호출 직전에 예약한다.
     *
     * <p>예전에는 {@code prepare()}가 예약했는데, basic 분석 도중 취소/타임아웃된 작업이
     * 예산만 소모하고 중단됐다. 9323bc71이 그 앞에 체크포인트를 뒀지만 prepare()~다음
     * 체크포인트 사이 창은 남았다. 이 단계를 취소/타임아웃 체크포인트 뒤로 옮겨 그 창까지
     * 없앤다. 예약을 되돌리는 경로가 없으므로 예약은 호출이 실제로 일어나는 시점에
     * 최대한 붙여 둔다.</p>
     *
     * @return 비어 있으면 진행, 값이 있으면 그 skipped 응답으로 대체. requireReal이면 예외.
     */
    Optional<VideoLlmEngineResponse> reserveBudgetOrSkip(
            String jobId,
            Plan plan,
            double chunkDurationSeconds,
            long maxDurationMinutes
    ) {
        SkipReason skipReason = reserveBudgetOrSkipReason(
                jobId,
                plan.durationSec(),
                chunkDurationSeconds,
                maxDurationMinutes
        );

        if (skipReason == null) {
            return Optional.empty();
        }

        if (plan.requireReal()) {
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_USAGE_LIMIT_EXCEEDED,
                    "실제 Video LLM 재분석을 실행할 수 없습니다. reason=" + skipReason
            );
        }

        log.info("[{}] Video LLM 분석을 건너뜁니다. ({})", jobId, skipReason);
        return Optional.of(createSkippedResponse(jobId, skipReason));
    }

    VideoLlmEngineResponse analyze(
            String jobId,
            String storedFilePath,
            String videoDownloadUrl,
            Plan plan
    ) {
        if (plan.skipped()) {
            return plan.skippedResponse();
        }

        VideoLlmEngineResponse response = videoLlmEngineClient.analyze(
                VideoLlmEngineRequest.defaultOption(
                        jobId,
                        storedFilePath,
                        plan.durationSec(),
                        videoDownloadUrl,
                        plan.requireReal()
                )
        );
        VideoLlmGenerationMode responseMode = resolveGenerationMode(response);

        if (plan.requireReal() && responseMode != VideoLlmGenerationMode.REAL) {
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_REAL_REQUIRED,
                    "실제 Video LLM 재분석이 REAL 응답을 반환하지 않았습니다. generationMode="
                            + responseMode
            );
        }

        // 엔진이 REAL/MOCK/FALLBACK 중 무엇으로 응답했는지 여기서 남깁니다. 완료 전 실패하면
        // completeStatus의 videoLlmGenerationMode 로그가 안 남으므로, 응답 시점에도 기록합니다.
        log.info("[{}] Video LLM 분석 응답을 받았습니다. generationMode={}", jobId, responseMode);
        return response;
    }

    static VideoLlmGenerationMode resolveGenerationMode(VideoLlmEngineResponse response) {
        Object rawMode = response == null || response.model() == null
                ? null
                : response.model().get("generationMode");
        return VideoLlmGenerationMode.from(rawMode);
    }

    private Double resolveDurationSec(String jobId, String storedFilePath) {
        try {
            Optional<Duration> duration = videoDurationProbe.probe(Path.of(storedFilePath));
            return duration
                    .map(value -> value.toMillis() / 1000.0)
                    .orElse(null);
        } catch (Exception exception) {
            log.warn(
                    "[{}] Video LLM durationSec 계산에 실패해 null로 전송합니다. path={}",
                    jobId,
                    storedFilePath,
                    exception
            );
            return null;
        }
    }

    private SkipReason reserveBudgetOrSkipReason(
            String jobId,
            Double durationSec,
            double chunkDurationSeconds,
            long maxDurationMinutes
    ) {
        Long ownerId = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::getOwnerId)
                .orElse(null);
        int estimatedNvidiaCallUnits = estimateNvidiaCallUnits(
                durationSec,
                chunkDurationSeconds,
                maxDurationMinutes
        );

        if (ownerId == null) {
            log.warn("[{}] Video LLM 일일 한도 확인을 위한 소유자 정보를 찾지 못해 한도 확인을 건너뜁니다.", jobId);
        }

        UserRateLimiter.VideoLlmBudgetReservation reservation =
                userRateLimiter.reserveVideoLlmBudget(
                        ownerId,
                        currentMonthKey(),
                        estimatedNvidiaCallUnits
                );

        if (reservation == UserRateLimiter.VideoLlmBudgetReservation.DAILY_LIMIT_EXCEEDED) {
            log.warn("[{}] 사용자(ownerId={})의 Video LLM 일일 호출 한도를 초과해 실제 호출을 생략합니다.", jobId, ownerId);
            return SkipReason.DAILY_LIMIT_EXCEEDED;
        }
        if (reservation == UserRateLimiter.VideoLlmBudgetReservation.MONTHLY_LIMIT_EXCEEDED) {
            log.warn(
                    "[{}] Video LLM 월간 호출 한도를 초과해 실제 호출을 생략합니다. estimatedNvidiaCallUnits={}",
                    jobId,
                    estimatedNvidiaCallUnits
            );
            return SkipReason.MONTHLY_BUDGET_EXCEEDED;
        }

        log.info(
                "[{}] Video LLM 월간 예산에서 NVIDIA 예상 호출 {}회를 예약했습니다. durationSec={}",
                jobId,
                estimatedNvidiaCallUnits,
                durationSec
        );
        return null;
    }

    private int estimateNvidiaCallUnits(
            Double durationSec,
            double chunkDurationSeconds,
            long maxDurationMinutes
    ) {
        double budgetedDurationSec = durationSec != null
                && Double.isFinite(durationSec)
                && durationSec > 0
                ? durationSec
                : Math.multiplyExact(maxDurationMinutes, 60L);
        return Math.max(1, (int) Math.ceil(budgetedDurationSec / chunkDurationSeconds));
    }

    private String currentMonthKey() {
        return YearMonth.now(clock).toString();
    }

    private VideoLlmEngineResponse createSkippedResponse(String jobId, SkipReason reason) {
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
}
