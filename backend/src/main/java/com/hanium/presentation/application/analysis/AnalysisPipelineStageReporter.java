package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.AnalysisStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 분석 파이프라인의 단계별 DB 상태와 Redis 진행률 전이를 한 경계에서 기록합니다. */
final class AnalysisPipelineStageReporter {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineStageReporter.class);

    private static final int BASIC_PERCENT = 10;
    private static final int VIDEO_LLM_PERCENT = 40;
    private static final int COMPACT_PERCENT = 60;
    private static final int OPENAI_PERCENT = 75;
    private static final int MERGE_PERCENT = 90;

    private final AnalysisJobStatusService analysisJobStatusService;
    private final AnalysisProgressService analysisProgressService;

    AnalysisPipelineStageReporter(
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService
    ) {
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisProgressService = analysisProgressService;
    }

    void start(String jobId) {
        analysisProgressService.start(jobId);
    }

    int beginBasicAnalysis(String jobId) {
        // QUEUED 작업 선점이 이미 DB 상태를 BASIC_ANALYZING으로 원자 전이했으므로
        // 여기서는 같은 상태를 다시 저장하지 않고 진행률 캐시만 맞춥니다.
        log.info(
                "STAGE_TRANSITION jobId={} step={} percent={}% 기본 분석 요청을 analysis-engine으로 전송합니다.",
                jobId,
                AnalysisStep.BASIC_ANALYSIS,
                BASIC_PERCENT
        );
        analysisProgressService.update(
                jobId,
                AnalysisStep.BASIC_ANALYSIS,
                AnalysisStatus.BASIC_ANALYZING,
                BASIC_PERCENT,
                "영상/음성 기본 분석을 실행하는 중입니다."
        );
        return BASIC_PERCENT;
    }

    int beginVideoLlmAnalysis(String jobId) {
        return transition(
                jobId,
                AnalysisStep.VIDEO_LLM_ANALYSIS,
                AnalysisStatus.VIDEO_LLM_ANALYZING,
                VIDEO_LLM_PERCENT,
                "Video LLM 분석을 실행하는 중입니다.",
                "Video LLM 분석 요청을 전송합니다."
        );
    }

    int beginCompacting(String jobId) {
        return transition(
                jobId,
                AnalysisStep.COMPACT_ANALYSIS,
                AnalysisStatus.COMPACTING,
                COMPACT_PERCENT,
                "분석 결과를 정리하는 중입니다.",
                "분석 결과를 정리(compact)하는 중입니다."
        );
    }

    int beginOpenAiFeedback(String jobId) {
        return transition(
                jobId,
                AnalysisStep.OPENAI_FEEDBACK,
                AnalysisStatus.OPENAI_GENERATING,
                OPENAI_PERCENT,
                "AI 피드백을 생성하는 중입니다.",
                "AI 피드백을 생성하는 중입니다."
        );
    }

    int beginResultMerge(String jobId) {
        return transition(
                jobId,
                AnalysisStep.RESULT_MERGE,
                AnalysisStatus.MERGING_RESULT,
                MERGE_PERCENT,
                "최종 결과를 병합하는 중입니다.",
                "최종 결과를 병합하는 중입니다."
        );
    }

    private int transition(
            String jobId,
            AnalysisStep step,
            AnalysisStatus status,
            int percent,
            String progressMessage,
            String logMessage
    ) {
        // DB 상태가 원본이고 Redis 진행률은 보조 캐시이므로 항상 DB를 먼저 커밋합니다.
        analysisJobStatusService.updateStatus(jobId, status);
        log.info("STAGE_TRANSITION jobId={} step={} percent={}% {}", jobId, step, percent, logMessage);
        analysisProgressService.update(jobId, step, status, percent, progressMessage);
        return percent;
    }
}
