package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.analysis.AnalysisCommandService;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.application.analysis.AnalysisQueryService;
import com.hanium.presentation.application.analysis.BasicAnalysisStepReader;
import com.hanium.presentation.application.analysis.VideoLlmReanalysisService;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.PracticeGoal;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.request.AnalysisRunRequest;
import com.hanium.presentation.presentation.dto.request.VideoLlmReanalysisRequest;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisUploadResponse;
import com.hanium.presentation.presentation.dto.response.VideoLlmReanalysisResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    // jobId는 JobIdGenerator가 생성하는 형식(yyyyMMddHHmmss-xxxxxxxx)과만 일치해야 합니다.
    // 형식을 검사하지 않으면 경로 조작(예: "../")이 섞인 값이 파일 경로 생성에 그대로 쓰일 수 있습니다.
    private static final String JOB_ID_PATTERN = "^\\d{14}-[0-9a-f]{8}$";
    private static final String JOB_ID_MESSAGE = "jobId 형식이 올바르지 않습니다.";

    private final AnalysisCommandService analysisCommandService;
    private final AnalysisQueryService analysisQueryService;
    private final AnalysisProgressService analysisProgressService;
    private final BasicAnalysisStepReader basicAnalysisStepReader;
    private final VideoLlmReanalysisService videoLlmReanalysisService;

    public AnalysisController(
            AnalysisCommandService analysisCommandService,
            AnalysisQueryService analysisQueryService,
            AnalysisProgressService analysisProgressService,
            BasicAnalysisStepReader basicAnalysisStepReader,
            VideoLlmReanalysisService videoLlmReanalysisService
    ) {
        this.analysisCommandService = analysisCommandService;
        this.analysisQueryService = analysisQueryService;
        this.analysisProgressService = analysisProgressService;
        this.basicAnalysisStepReader = basicAnalysisStepReader;
        this.videoLlmReanalysisService = videoLlmReanalysisService;
    }

    @PostMapping("/upload")
    public ApiResponse<AnalysisUploadResponse> uploadVideo(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String baselineJobId,
            @RequestParam(required = false) PracticeGoal practiceGoal,
            Authentication authentication
    ) {
        AnalysisUploadResponse response = analysisCommandService.uploadVideo(
                file,
                getCurrentUserId(authentication),
                baselineJobId,
                practiceGoal
        );

        return ApiResponse.success(
                "영상 업로드가 완료되었습니다.",
                response
        );
    }

    private Long getCurrentUserId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("인증 정보에서 사용자 id를 찾을 수 없습니다.");
    }

    @GetMapping("/{jobId}/status")
    public ApiResponse<AnalysisStatusResponse> getAnalysisStatus(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        AnalysisStatusResponse response = analysisQueryService.getStatus(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success(
                "분석 상태 조회가 완료되었습니다.",
                response
        );
    }

    @GetMapping("/{jobId}/progress")
    public ApiResponse<Map<String, Object>> getAnalysisProgress(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        AnalysisStatusResponse statusResponse = analysisQueryService.getStatus(
                jobId,
                getCurrentUserId(authentication)
        );
        Map<String, Object> progress = analysisProgressService.getProgress(jobId);

        if (progress == null) {
            // Redis에 진행률 캐시가 없으면(만료되었거나 아직 시작 전이면) DB에 저장된
            // 최종 상태를 기준으로 대략적인 진행률을 만들어 대신 반환합니다.
            progress = createFallbackProgress(statusResponse);
        }

        enrichWithBasicAnalysisStep(jobId, statusResponse, progress);

        return ApiResponse.success(
                "분석 진행률 조회가 완료되었습니다.",
                progress
        );
    }

    // 기본 분석(BASIC_ANALYZING)은 백엔드가 분석 엔진을 한 번의 동기 호출로 기다리는 구간이라,
    // 파이프라인 단계 기준으로는 10%에 멈춰 보입니다. 분석 엔진이 공유 볼륨에 남긴 세부 단계
    // (progress.json)가 있으면, 그 단계 정보를 덧붙이고 진행률을 10~38% 구간으로 세분화합니다.
    private void enrichWithBasicAnalysisStep(
            String jobId,
            AnalysisStatusResponse statusResponse,
            Map<String, Object> progress
    ) {
        if (statusResponse.status() != AnalysisStatus.BASIC_ANALYZING) {
            return;
        }

        basicAnalysisStepReader.read(jobId).ifPresent(step -> {
            progress.put("basicAnalysisStep", step);

            Integer stepNo = toInt(step.get("stepNo"));
            Integer totalSteps = toInt(step.get("totalSteps"));
            Object label = step.get("label");

            if (label instanceof String labelText && !labelText.isBlank()) {
                progress.put("message", labelText);
            }

            if (stepNo != null && totalSteps != null && totalSteps > 0) {
                progress.put("percent", basicAnalysisPercent(stepNo, totalSteps));
            }
        });
    }

    // 기본 분석 세부 단계를 파이프라인 BASIC(10%)와 다음 단계(40%) 사이의 10~38% 구간에
    // 매핑합니다. 마지막 단계가 38%에 도달하도록 (totalSteps - 1)로 나눕니다
    // (totalSteps <= 1이면 0으로 나누지 않도록 분모를 1로 보정).
    static int basicAnalysisPercent(int stepNo, int totalSteps) {
        int denominator = Math.max(1, totalSteps - 1);
        int completed = Math.max(0, Math.min(stepNo, totalSteps) - 1);
        int percent = 10 + (int) Math.round(completed / (double) denominator * 28);
        return Math.max(10, Math.min(percent, 38));
    }

    private Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Map<String, Object> createFallbackProgress(AnalysisStatusResponse statusResponse) {
        Map<String, Object> fallback = new LinkedHashMap<>();

        fallback.put("jobId", statusResponse.jobId());
        fallback.put("step", null);
        fallback.put("status", statusResponse.status().name());
        fallback.put("statusDescription", statusResponse.statusDescription());
        fallback.put(
                "percent",
                switch (statusResponse.status()) {
                    case UPLOADED -> 0;
                    case QUEUED -> 5;
                    case BASIC_ANALYZING -> 10;
                    case VIDEO_LLM_ANALYZING -> 40;
                    case COMPACTING -> 60;
                    case OPENAI_GENERATING -> 75;
                    case MERGING_RESULT -> 90;
                    case COMPLETED -> 100;
                    case FAILED -> 0;
                    case CANCELLED -> 0;
                    case DEAD_LETTER -> 0;
                }
        );
        fallback.put("message", "Redis 진행률 캐시가 없어 DB에 저장된 상태 기준으로 표시합니다.");
        fallback.put("updatedAt", statusResponse.startedAt());

        return fallback;
    }

    @PostMapping("/{jobId}/run")
    public ApiResponse<AnalysisStatusResponse> runAnalysis(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            @RequestBody(required = false) AnalysisRunRequest request,
            Authentication authentication
    ) {
        AnalysisRunRequest runRequest = request == null
                ? new AnalysisRunRequest(true, true)
                : request;

        AnalysisStatusResponse response = analysisCommandService.runAnalysis(
                jobId,
                getCurrentUserId(authentication),
                runRequest.isUseVideoLlm(),
                runRequest.isUseOpenAi()
        );

        return ApiResponse.success(
                "분석 실행 요청이 접수되었습니다.",
                response
        );
    }

    @PostMapping("/{jobId}/retry")
    public ApiResponse<AnalysisStatusResponse> retryAnalysis(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            @RequestBody(required = false) AnalysisRunRequest request,
            Authentication authentication
    ) {
        Long ownerId = getCurrentUserId(authentication);
        AnalysisStatusResponse response = request == null
                ? analysisCommandService.retryAnalysis(jobId, ownerId)
                : analysisCommandService.retryAnalysis(
                        jobId,
                        ownerId,
                        request.useVideoLlm(),
                        request.useOpenAi()
                );

        return ApiResponse.success(
                "분석 재시도가 완료되었습니다.",
                response
        );
    }

    @PostMapping("/{sourceJobId}/video-llm-reanalysis")
    public ResponseEntity<ApiResponse<VideoLlmReanalysisResponse>> requestVideoLlmReanalysis(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String sourceJobId,
            @RequestHeader("Idempotency-Key")
            @Pattern(
                    regexp = "^[A-Za-z0-9._:-]{16,128}$",
                    message = "Idempotency-Key는 16~128자의 영문, 숫자, '.', '_', ':', '-'만 사용할 수 있습니다."
            )
            String idempotencyKey,
            @RequestBody(required = false) VideoLlmReanalysisRequest request,
            Authentication authentication
    ) {
        VideoLlmReanalysisRequest reanalysisRequest = request == null
                ? new VideoLlmReanalysisRequest(true)
                : request;
        VideoLlmReanalysisResponse response = videoLlmReanalysisService.requestReanalysis(
                sourceJobId,
                getCurrentUserId(authentication),
                idempotencyKey,
                reanalysisRequest.isUseOpenAi()
        );

        HttpStatus status = response.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        String message = response.reused()
                ? "동일한 실제 Video LLM 재분석 요청을 반환했습니다."
                : "실제 Video LLM 재분석 요청이 접수되었습니다.";
        return ResponseEntity.status(status).body(ApiResponse.success(message, response));
    }

    @PostMapping("/{jobId}/cancel")
    public ApiResponse<AnalysisStatusResponse> cancelAnalysis(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        AnalysisStatusResponse response = analysisCommandService.cancelAnalysis(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success(
                "분석 취소 요청이 처리되었습니다. 대기 중이었다면 즉시 취소되고, 진행 중이었다면 "
                        + "현재 단계가 끝나면 취소 상태로 반영됩니다.",
                response
        );
    }
}
