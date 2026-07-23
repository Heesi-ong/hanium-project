package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;

import java.util.LinkedHashMap;
import java.util.Map;

public record AnalysisResultResponse(
        String jobId,
        Map<String, Object> result,
        AnalysisKind analysisKind,
        String sourceJobId,
        String latestReanalysisJobId,
        VideoLlmGenerationMode videoLlmGenerationMode,
        String dataIssue,
        String dataIssueDescription
) {

    @SuppressWarnings("unchecked")
    public static AnalysisResultResponse of(
            AnalysisJob analysisJob,
            Map<String, Object> result,
            String latestReanalysisJobId
    ) {
        Map<String, Object> normalizedResult = new LinkedHashMap<>(
                ResultSummaryResponse.normalizeFinalResult(result)
        );
        normalizedResult.put("status", analysisJob.getStatus().name());
        normalizedResult.put("failReason", analysisJob.getFailReason());
        // normalizeFinalResult가 이미 만들어 둔 scoreSummary/feedback을 그대로 재사용합니다.
        // 원본 result를 다시 넘겨 같은 추출 로직을 한 번 더 돌리지 않습니다.
        String dataIssue = ResultSummaryResponse.resolveDataIssue(
                (Map<String, Object>) normalizedResult.get("scoreSummary"),
                (Map<String, Object>) normalizedResult.get("feedback")
        );

        return new AnalysisResultResponse(
                analysisJob.getJobId(),
                normalizedResult,
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                latestReanalysisJobId,
                analysisJob.getVideoLlmGenerationMode(),
                dataIssue,
                ResultSummaryResponse.resolveDataIssueDescription(dataIssue)
        );
    }

    public static AnalysisResultResponse statusOnly(
            AnalysisJob analysisJob,
            String latestReanalysisJobId
    ) {
        Map<String, Object> result = new LinkedHashMap<>(
                ResultSummaryResponse.normalizeFinalResult(Map.of())
        );
        result.put("status", analysisJob.getStatus().name());
        result.put("failReason", analysisJob.getFailReason());
        return new AnalysisResultResponse(
                analysisJob.getJobId(),
                result,
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                latestReanalysisJobId,
                analysisJob.getVideoLlmGenerationMode(),
                null,
                null
        );
    }

    public static AnalysisResultResponse resultDataUnavailable(
            AnalysisJob analysisJob,
            String latestReanalysisJobId
    ) {
        Map<String, Object> result = new LinkedHashMap<>(
                ResultSummaryResponse.normalizeFinalResult(Map.of())
        );
        result.put("status", analysisJob.getStatus().name());
        result.put("failReason", analysisJob.getFailReason());
        return new AnalysisResultResponse(
                analysisJob.getJobId(),
                result,
                analysisJob.getAnalysisKind(),
                analysisJob.getSourceJobId(),
                latestReanalysisJobId,
                analysisJob.getVideoLlmGenerationMode(),
                "RESULT_DATA_UNAVAILABLE",
                ResultSummaryResponse.resolveDataIssueDescription("RESULT_DATA_UNAVAILABLE")
        );
    }
}
