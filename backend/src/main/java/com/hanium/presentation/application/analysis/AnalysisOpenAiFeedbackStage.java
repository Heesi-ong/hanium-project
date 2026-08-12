package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 피드백 단계의 재사용, 신규 생성, 명시적 생략과 결과 저장을 한 경계에서 처리합니다.
 */
final class AnalysisOpenAiFeedbackStage {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOpenAiFeedbackStage.class);
    private static final String SKIP_REASON = "사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.";

    private final ResultCommandService resultCommandService;
    private final OpenAiClient openAiClient;

    AnalysisOpenAiFeedbackStage(
            ResultCommandService resultCommandService,
            OpenAiClient openAiClient
    ) {
        this.resultCommandService = resultCommandService;
        this.openAiClient = openAiClient;
    }

    OpenAiFeedbackResponse generateAndSave(
            String jobId,
            boolean useOpenAi,
            Map<String, Object> compactAnalysis
    ) {
        OpenAiFeedbackResponse response = useOpenAi
                ? generateOrReuse(jobId, compactAnalysis)
                : createSkippedResponse(jobId);

        resultCommandService.saveOpenAiFeedbackResult(jobId, response);
        return response;
    }

    private OpenAiFeedbackResponse generateOrReuse(
            String jobId,
            Map<String, Object> compactAnalysis
    ) {
        OpenAiFeedbackResponse response = resultCommandService.loadExistingRealOpenAiFeedback(jobId)
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

        log.info("[{}] AI 피드백 생성이 끝났습니다. (mode={})", jobId, response.generationMode());
        return response;
    }

    private OpenAiFeedbackResponse createSkippedResponse(String jobId) {
        log.info("[{}] OpenAI 피드백 생성을 건너뜁니다. (useOpenAi=false)", jobId);
        return OpenAiFeedbackResponse.skipped(
                jobId,
                SKIP_REASON,
                "OpenAI 피드백 생성을 사용하지 않았습니다. 기본 분석 결과만 저장되었습니다.",
                List.of("기본 분석 결과가 정상적으로 생성되었습니다."),
                List.of("OpenAI 피드백을 활성화하면 더 구체적인 개선점을 받을 수 있습니다."),
                List.of(),
                List.of()
        );
    }
}
