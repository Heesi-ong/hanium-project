package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.YearMonth;
import java.util.List;

@Component
public class OpenAiCoachClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCoachClient.class);
    private static final String RESPONSES_API_PATH = "/v1/responses";
    private static final String MOCK_REPLY =
            "지금은 AI 코치 응답을 생성할 수 없어 기본 안내를 드립니다. "
                    + "분석 결과 화면의 강점/개선점/연습 계획을 참고해 연습해보세요. "
                    + "잠시 후 다시 시도해주시면 더 구체적인 답변을 드릴 수 있습니다.";

    private final OpenAiProperties openAiProperties;
    private final CoachPromptBuilder coachPromptBuilder;
    private final RestClient openAiRestClient;
    private final UserRateLimiter userRateLimiter;

    public OpenAiCoachClient(
            OpenAiProperties openAiProperties,
            CoachPromptBuilder coachPromptBuilder,
            RestClient openAiRestClient,
            UserRateLimiter userRateLimiter
    ) {
        this.openAiProperties = openAiProperties;
        this.coachPromptBuilder = coachPromptBuilder;
        this.openAiRestClient = openAiRestClient;
        this.userRateLimiter = userRateLimiter;
    }

    public OpenAiCoachReplyResponse generateReply(OpenAiCoachChatRequest request) {
        if (!openAiProperties.canUseRealApi()) {
            return mockReply(request, resolveMockReason());
        }

        // 코치 채팅도 기존 피드백 생성과 같은 전역 월간 OpenAI 예산("openai-monthly")을 함께 소비한다.
        // 이 버킷은 기능과 무관하게 조직 전체 OpenAI 지출 총량을 통제하기 위한 것이므로, 채팅이라고
        // 예외를 두면 예산 통제가 우회된다. 사용자별 일일 한도는 이와 별개로 서비스 계층에서 적용된다.
        if (!userRateLimiter.tryConsume("openai-monthly", currentMonthKey())) {
            return mockReply(request, "monthly OpenAI budget exceeded");
        }

        try {
            return generateRealReply(request);
        } catch (RuntimeException exception) {
            return mockReply(request, resolveFallbackReason(exception));
        }
    }

    private String currentMonthKey() {
        return YearMonth.now().toString();
    }

    private OpenAiCoachReplyResponse generateRealReply(OpenAiCoachChatRequest request) {
        List<OpenAiResponsesApiRequest.InputMessage> messages = coachPromptBuilder.buildMessages(
                request.compactAnalysis(),
                request.history(),
                request.newUserMessage()
        );

        OpenAiResponsesApiRequest apiRequest = OpenAiResponsesApiRequest.createChat(
                openAiProperties.getModel(),
                messages
        );

        OpenAiResponsesApiResponse apiResponse = openAiRestClient
                .post()
                .uri(RESPONSES_API_PATH)
                .body(apiRequest)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (httpRequest, httpResponse) -> {
                            throw new IllegalStateException(
                                    "OpenAI API 호출 실패: HTTP "
                                            + httpResponse.getStatusCode().value()
                            );
                        }
                )
                .body(OpenAiResponsesApiResponse.class);

        if (apiResponse == null) {
            throw new IllegalStateException("OpenAI API 응답이 비어 있습니다.");
        }

        logOpenAiUsage(request, apiResponse);

        if (!apiResponse.isCompleted()) {
            throw new IllegalStateException(
                    "OpenAI API 응답 상태가 completed가 아닙니다. status="
                            + apiResponse.status()
            );
        }

        String outputText = apiResponse.extractOutputText();

        if (outputText == null || outputText.isBlank()) {
            throw new IllegalStateException("OpenAI API 응답 텍스트가 비어 있습니다.");
        }

        return OpenAiCoachReplyResponse.real(
                request.jobId(),
                openAiProperties.getModel(),
                outputText
        );
    }

    private void logOpenAiUsage(
            OpenAiCoachChatRequest request,
            OpenAiResponsesApiResponse apiResponse
    ) {
        OpenAiResponsesApiResponse.Usage usage = apiResponse.usage();

        if (usage == null) {
            log.info(
                    "OPENAI_COACH_USAGE jobId={} model={} usage=none",
                    request.jobId(),
                    openAiProperties.getModel()
            );
            return;
        }

        log.info(
                "OPENAI_COACH_USAGE jobId={} model={} inputTokens={} outputTokens={} totalTokens={}",
                request.jobId(),
                openAiProperties.getModel(),
                usage.input_tokens(),
                usage.output_tokens(),
                usage.total_tokens()
        );
    }

    private OpenAiCoachReplyResponse mockReply(
            OpenAiCoachChatRequest request,
            String reason
    ) {
        return OpenAiCoachReplyResponse.mock(
                request.jobId(),
                openAiProperties.getModel(),
                reason,
                MOCK_REPLY
        );
    }

    private String resolveMockReason() {
        if (!openAiProperties.isEnabled()) {
            return "openai.enabled=false";
        }

        if (!openAiProperties.hasApiKey()) {
            return "OPENAI_API_KEY is empty";
        }

        return "mock mode";
    }

    private String resolveFallbackReason(RuntimeException exception) {
        if (exception instanceof RestClientException) {
            return "OpenAI HTTP client error: " + exception.getMessage();
        }

        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return exception.getMessage();
    }
}
