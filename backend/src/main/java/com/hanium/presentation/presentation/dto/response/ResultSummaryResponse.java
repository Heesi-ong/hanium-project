package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.common.contract.ResultSchemaVersion;
import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.PracticeGoal;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.domain.video.entity.UploadedVideo;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResultSummaryResponse(
        String jobId,
        String memo,
        AnalysisStatus status,
        String statusDescription,
        String originalFileName,
        String fileName,
        Long fileSize,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        ScoreSummary scoreSummary,
        FeedbackSummary feedback,
        Map<String, Object> visualAnalysis,
        Map<String, Object> pipeline,
        AnalysisKind analysisKind,
        String sourceJobId,
        VideoLlmGenerationMode videoLlmGenerationMode,
        String baselineJobId,
        PracticeGoal practiceGoal,
        // 정상 항목은 null입니다. UploadedVideo 레코드가 없는 등 데이터 부분 손상이 있을 때만
        // 값이 채워지며, 목록 조회는 이 항목을 실패시키지 않고 손상 사실만 표시한 채 반환합니다.
        String dataIssue,
        String dataIssueDescription
) {

    public static ResultSummaryResponse of(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo
    ) {
        return of(
                analysisJob,
                uploadedVideo,
                Map.of()
        );
    }

    public static ResultSummaryResponse of(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo,
            Map<String, Object> finalResult
    ) {
        ScoreSummary scoreSummary = extractScoreSummary(finalResult);
        Map<String, Object> pipeline = extractPipeline(finalResult);
        FeedbackSummary feedback = extractFeedback(finalResult, pipeline);
        Map<String, Object> visualAnalysis = extractVisualAnalysisSummary(finalResult, pipeline);
        String dataIssue = analysisJob.getStatus() == AnalysisStatus.COMPLETED
                ? resolveDataIssue(scoreSummary, feedback)
                : null;

        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getMemo(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getFileSize(),
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt(),
                scoreSummary,
                feedback,
                visualAnalysis,
                pipeline,
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                analysisJob.getVideoLlmGenerationMode(),
                analysisJob.getBaselineJobId(),
                analysisJob.getPracticeGoal(),
                dataIssue,
                resolveDataIssueDescription(dataIssue)
        );
    }

    // 업로드된 영상 레코드를 찾지 못한(데이터 정합성이 깨진) 작업을 위한 항목입니다.
    // 목록 전체를 실패시키는 대신, 이 항목만 손상 상태로 표시해 나머지 결과는 정상 반환합니다.
    public static ResultSummaryResponse missingVideo(
            AnalysisJob analysisJob,
            Map<String, Object> finalResult
    ) {
        Map<String, Object> pipeline = extractPipeline(finalResult);

        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getMemo(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                "(영상 정보 없음)",
                "(영상 정보 없음)",
                0L,
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt(),
                extractScoreSummary(finalResult),
                extractFeedback(finalResult, pipeline),
                extractVisualAnalysisSummary(finalResult, pipeline),
                pipeline,
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                analysisJob.getVideoLlmGenerationMode(),
                analysisJob.getBaselineJobId(),
                analysisJob.getPracticeGoal(),
                "MISSING_VIDEO",
                "업로드된 영상 정보를 찾을 수 없습니다. 관리자에게 문의하세요."
        );
    }

    // 분석 작업 상태는 COMPLETED인데 최종 결과 파일을 찾지 못했거나 읽지 못한(예: 로컬↔MinIO
    // 이중화 과정에서 파일이 유실된) 작업을 위한 항목입니다. 점수/피드백이 전부 기본값(0/UNKNOWN)
    // 이라는 것만으로는 사용자·관리자 화면에서 "정상인데 낮은 점수"와 구분되지 않으므로,
    // MISSING_VIDEO와 마찬가지로 손상 상태를 명시적으로 표시합니다.
    public static ResultSummaryResponse missingResultData(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo
    ) {
        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getMemo(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getFileSize(),
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt(),
                ScoreSummary.empty(),
                createUnknownFeedback(),
                createUnknownVisualAnalysis(),
                createUnknownPipeline(),
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                analysisJob.getVideoLlmGenerationMode(),
                analysisJob.getBaselineJobId(),
                analysisJob.getPracticeGoal(),
                "RESULT_DATA_UNAVAILABLE",
                "분석은 완료됐지만 결과 파일을 찾을 수 없습니다. 관리자에게 문의하세요."
        );
    }

    public static Map<String, Object> normalizeFinalResult(
            Map<String, Object> finalResult
    ) {
        Map<String, Object> normalizedResult = finalResult == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(finalResult);
        Map<String, Object> pipeline = extractPipeline(finalResult);

        normalizedResult.put(
                ResultSchemaVersion.FIELD,
                ResultSchemaVersion.resolve(finalResult)
        );
        normalizedResult.put("scoreSummary", extractScoreSummary(finalResult));
        normalizedResult.put("analysisQuality", extractAnalysisQuality(finalResult));
        normalizedResult.put("feedback", extractFeedback(finalResult, pipeline));
        normalizedResult.put("visualAnalysis", extractVisualAnalysisDetail(finalResult, pipeline));
        normalizedResult.put("pipeline", pipeline);

        return normalizedResult;
    }

    private static AnalysisQualitySummary extractAnalysisQuality(
            Map<String, Object> finalResult
    ) {
        if (finalResult == null) {
            return AnalysisQualitySummary.unavailable();
        }

        Object quality = finalResult.get("analysisQuality");
        if (quality instanceof AnalysisQualitySummary typedQuality) {
            return typedQuality;
        }
        if (quality instanceof Map<?, ?> rawMap) {
            return AnalysisQualitySummary.from(rawMap);
        }
        return AnalysisQualitySummary.unavailable();
    }

    private static ScoreSummary extractScoreSummary(
            Map<String, Object> finalResult
    ) {
        if (finalResult == null) {
            return ScoreSummary.empty();
        }

        Object scoreSummary = finalResult.get("scoreSummary");

        if (scoreSummary instanceof Map<?, ?> rawMap) {
            return ScoreSummary.from(rawMap);
        }

        return ScoreSummary.empty();
    }

    // 정규화 규칙(저장된 값이 비어있거나 UNKNOWN이면 pipeline의 OpenAI 메타데이터로 보완)은
    // 이제 FeedbackSummary.from()/fromPipeline()에 있다(P2-05, ScoreSummary에 이은 두
    // 번째 typed DTO 전환). strengths/improvements를 항상 포함하는 것도 그대로 유지된다
    // (2026-07-23 발견 buggy 이력: 이전에는 여기서 빠져 있어 실제로 생성됐어도 결과 화면에는
    // 항상 "표시할 강점/개선점이 없습니다"로만 보였다).
    private static FeedbackSummary extractFeedback(
            Map<String, Object> finalResult,
            Map<String, Object> pipeline
    ) {
        if (finalResult == null) {
            return createFeedbackFromPipeline(pipeline);
        }

        Object feedback = finalResult.get("feedback");

        if (feedback instanceof FeedbackSummary typedFeedback) {
            return typedFeedback;
        }

        if (feedback instanceof Map<?, ?> rawMap) {
            return FeedbackSummary.from(rawMap, pipeline);
        }

        return createFeedbackFromPipeline(pipeline);
    }

    private static Map<String, Object> extractVisualAnalysisSummary(
            Map<String, Object> finalResult,
            Map<String, Object> pipeline
    ) {
        if (finalResult == null) {
            return createVisualAnalysisFromPipeline(pipeline);
        }

        Object visualAnalysis = finalResult.get("visualAnalysis");

        if (visualAnalysis instanceof Map<?, ?>) {
            Map<String, Object> source = JsonMapSupport.copyStringKeyedMap(visualAnalysis);
            Map<String, Object> normalizedVisualAnalysis = new LinkedHashMap<>();
            Object model = source.get("model");

            if (model instanceof Map<?, ?>) {
                Map<String, Object> modelSource = JsonMapSupport.copyStringKeyedMap(model);
                Map<String, Object> normalizedModel = new LinkedHashMap<>();
                Object pipelineGenerationMode = pipeline.get("videoLlmGenerationMode");
                Object pipelineAnalysis = pipeline.get("videoLlmAnalysis");

                normalizedModel.put("name", getFirstMeaningful(
                        getOrDefault(modelSource, "name", "-"),
                        pipelineAnalysis,
                        "-"
                ));
                normalizedModel.put("version", getOrDefault(modelSource, "version", "-"));
                normalizedModel.put("generationMode", getFirstMeaningful(
                        getOrDefault(modelSource, "generationMode", "UNKNOWN"),
                        pipelineGenerationMode,
                        "UNKNOWN"
                ));
                normalizedVisualAnalysis.put("model", normalizedModel);

                return normalizedVisualAnalysis;
            }
        }

        return createVisualAnalysisFromPipeline(pipeline);
    }

    private static Map<String, Object> extractVisualAnalysisDetail(
            Map<String, Object> finalResult,
            Map<String, Object> pipeline
    ) {
        if (finalResult == null) {
            return createVisualAnalysisFromPipeline(pipeline);
        }

        Object visualAnalysis = finalResult.get("visualAnalysis");

        if (visualAnalysis instanceof Map<?, ?>) {
            Map<String, Object> source = JsonMapSupport.copyStringKeyedMap(visualAnalysis);
            Map<String, Object> normalizedVisualAnalysis = new LinkedHashMap<>(source);
            Object model = source.get("model");
            Object pipelineGenerationMode = pipeline.get("videoLlmGenerationMode");
            Object pipelineAnalysis = pipeline.get("videoLlmAnalysis");

            if (model instanceof Map<?, ?>) {
                Map<String, Object> modelSource = JsonMapSupport.copyStringKeyedMap(model);
                Map<String, Object> normalizedModel = new LinkedHashMap<>(modelSource);

                normalizedModel.put("name", getFirstMeaningful(
                        getOrDefault(modelSource, "name", "-"),
                        pipelineAnalysis,
                        "-"
                ));
                normalizedModel.put("version", getOrDefault(modelSource, "version", "-"));
                normalizedModel.put("generationMode", getFirstMeaningful(
                        getOrDefault(modelSource, "generationMode", "UNKNOWN"),
                        pipelineGenerationMode,
                        "UNKNOWN"
                ));
                normalizedVisualAnalysis.put("model", normalizedModel);

                return normalizedVisualAnalysis;
            }

            normalizedVisualAnalysis.put("model", createVisualAnalysisFromPipeline(pipeline).get("model"));

            return normalizedVisualAnalysis;
        }

        return createVisualAnalysisFromPipeline(pipeline);
    }

    private static Map<String, Object> extractPipeline(
            Map<String, Object> finalResult
    ) {
        if (finalResult == null) {
            return createUnknownPipeline();
        }

        Object pipeline = finalResult.get("pipeline");

        if (pipeline instanceof Map<?, ?>) {
            Map<String, Object> source = JsonMapSupport.copyStringKeyedMap(pipeline);
            Map<String, Object> normalizedPipeline = new LinkedHashMap<>();

            normalizedPipeline.put("videoLlmAnalysis", getOrDefault(source, "videoLlmAnalysis", "-"));
            normalizedPipeline.put("videoLlmGenerationMode", getOrDefault(source, "videoLlmGenerationMode", "UNKNOWN"));
            normalizedPipeline.put("openAiGenerationMode", getOrDefault(source, "openAiGenerationMode", "UNKNOWN"));
            normalizedPipeline.put("openAiModel", getOrDefault(source, "openAiModel", "-"));
            normalizedPipeline.put("openAiRealApiUsed", getOrDefault(source, "openAiRealApiUsed", false));
            normalizedPipeline.put("openAiFallbackReason", getOrDefault(source, "openAiFallbackReason", "-"));

            return normalizedPipeline;
        }

        return createUnknownPipeline();
    }

    private static FeedbackSummary createUnknownFeedback() {
        return FeedbackSummary.unknown();
    }

    private static FeedbackSummary createFeedbackFromPipeline(
            Map<String, Object> pipeline
    ) {
        return FeedbackSummary.fromPipeline(pipeline);
    }

    private static Map<String, Object> createUnknownVisualAnalysis() {
        Map<String, Object> visualAnalysis = new LinkedHashMap<>();
        Map<String, Object> model = new LinkedHashMap<>();

        model.put("name", "-");
        model.put("version", "-");
        model.put("generationMode", "UNKNOWN");
        visualAnalysis.put("model", model);

        return visualAnalysis;
    }

    private static Map<String, Object> createVisualAnalysisFromPipeline(
            Map<String, Object> pipeline
    ) {
        Map<String, Object> visualAnalysis = new LinkedHashMap<>();
        Map<String, Object> model = new LinkedHashMap<>();

        model.put("name", getFirstMeaningful(
                pipeline.get("videoLlmAnalysis"),
                "-",
                "-"
        ));
        model.put("version", "-");
        model.put("generationMode", getFirstMeaningful(
                pipeline.get("videoLlmGenerationMode"),
                "UNKNOWN",
                "UNKNOWN"
        ));
        visualAnalysis.put("model", model);

        return visualAnalysis;
    }

    private static Map<String, Object> createUnknownPipeline() {
        Map<String, Object> pipeline = new LinkedHashMap<>();

        pipeline.put("videoLlmAnalysis", "-");
        pipeline.put("videoLlmGenerationMode", "UNKNOWN");
        pipeline.put("openAiGenerationMode", "UNKNOWN");
        pipeline.put("openAiModel", "-");
        pipeline.put("openAiRealApiUsed", false);
        pipeline.put("openAiFallbackReason", "-");

        return pipeline;
    }

    // normalizeFinalResult()가 이미 만들어 둔 scoreSummary/feedback을 그대로 받습니다.
    // 여기서 다시 extractScoreSummary/extractFeedback을 호출하면(과거에는 그렇게 했습니다)
    // 이미 정규화된 결과를 놓고 같은 추출 로직을 또 한 번 돌리는 것이라 매 결과 조회마다
    // 불필요한 이중 작업이 됩니다(값 자체는 정규화가 이미 끝나 있어 멱등이라 틀린 값이
    // 나오지는 않았지만, 낭비였습니다).
    static String resolveDataIssue(
            ScoreSummary scoreSummary,
            FeedbackSummary feedback
    ) {
        String level = scoreSummary.level();
        String generationMode = feedback.generationMode();
        String overall = feedback.overall();

        if ("-".equals(level) || isBlank(overall) || "UNKNOWN".equals(generationMode)) {
            return "RESULT_DATA_INCOMPLETE";
        }

        return null;
    }

    public static String resolveDataIssueDescription(String dataIssue) {
        if ("RESULT_DATA_INCOMPLETE".equals(dataIssue)) {
            return "분석 결과 파일은 있지만 점수 또는 피드백 데이터가 불완전합니다. 재분석이 필요할 수 있습니다.";
        }

        if ("RESULT_DATA_UNAVAILABLE".equals(dataIssue)) {
            return "분석은 완료됐지만 결과 파일을 찾을 수 없습니다. 관리자에게 문의하세요.";
        }

        if ("MISSING_VIDEO".equals(dataIssue)) {
            return "업로드된 영상 정보를 찾을 수 없습니다. 관리자에게 문의하세요.";
        }

        return null;
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static Object getOrDefault(
            Map<String, Object> map,
            String key,
            Object defaultValue
    ) {
        Object value = map.get(key);
        return value == null ? defaultValue : value;
    }

    private static Object getFirstMeaningful(
            Object primaryValue,
            Object fallbackValue,
            Object defaultValue
    ) {
        if (isMeaningfulValue(primaryValue)) {
            return primaryValue;
        }

        if (isMeaningfulValue(fallbackValue)) {
            return fallbackValue;
        }

        return defaultValue;
    }

    private static boolean isMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }

        String text = value.toString();
        return !text.isBlank() && !"-".equals(text) && !"UNKNOWN".equals(text);
    }
}
