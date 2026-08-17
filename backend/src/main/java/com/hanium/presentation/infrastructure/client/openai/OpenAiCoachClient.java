package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.CoachLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.YearMonth;
import java.util.List;

// coach.llm.provider 설정에 따라 OpenAI 또는 NVIDIA(build.nvidia.com) 중 하나를 호출합니다.
// 둘 다 표준 Chat Completions 형식(POST /chat/completions)을 쓰므로 요청/응답 처리 코드는
// provider와 무관하게 공유되고, 실제로 호출할 base URL/키/모델만 CoachLlmProperties가 갈라줍니다.
@Component
public class OpenAiCoachClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCoachClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final double TEMPERATURE = 0.4;
    private static final int MAX_TOKENS = 800;
    private static final String MOCK_REPLY =
            "지금은 AI 코치 응답을 생성할 수 없어 기본 안내를 드립니다. "
                    + "분석 결과 화면의 강점/개선점/연습 계획을 참고해 연습해보세요. "
                    + "잠시 후 다시 시도해주시면 더 구체적인 답변을 드릴 수 있습니다.";

    private final OpenAiProperties openAiProperties;
    private final CoachLlmProperties coachLlmProperties;
    private final CoachPromptBuilder coachPromptBuilder;
    private final RestClient coachLlmRestClient;
    private final UserRateLimiter userRateLimiter;

    public OpenAiCoachClient(
            OpenAiProperties openAiProperties,
            CoachLlmProperties coachLlmProperties,
            CoachPromptBuilder coachPromptBuilder,
            @Qualifier("coachLlmRestClient") RestClient coachLlmRestClient,
            UserRateLimiter userRateLimiter
    ) {
        this.openAiProperties = openAiProperties;
        this.coachLlmProperties = coachLlmProperties;
        this.coachPromptBuilder = coachPromptBuilder;
        this.coachLlmRestClient = coachLlmRestClient;
        this.userRateLimiter = userRateLimiter;
    }

    public OpenAiCoachReplyResponse generateReply(OpenAiCoachChatRequest request) {
        if (!canUseRealApi()) {
            return mockReply(request, resolveMockReason());
        }

        // NVIDIA로 임시 대체 중일 때는 OpenAI 전용 월간 예산("openai-monthly")과 무관한
        // 호출이므로 그 버킷을 소비/검사하지 않는다. openai provider일 때만 기존처럼
        // 조직 전체 OpenAI 지출 총량을 통제한다.
        if (!coachLlmProperties.isNvidiaProvider()
                && !userRateLimiter.tryConsume("openai-monthly", currentMonthKey())) {
            return mockReply(request, "monthly OpenAI budget exceeded");
        }

        try {
            return generateRealReply(request);
        } catch (RestClientException firstAttemptException) {
            // 컨테이너 기동 직후 첫 외부 HTTPS 호출(DNS/TLS 협상)이 유독 느려 타임아웃되는
            // 경우를 실제로 관찰했다(2026-07-23) - 이후 호출은 연결이 워밍업돼 정상 동작한다.
            // 네트워크/IO 계열 오류에 한해 한 번만 재시도하고, 그래도 실패하면 폴백한다.
            try {
                return generateRealReply(request);
            } catch (RuntimeException retryException) {
                return fallbackToMock(request, retryException);
            }
        } catch (RuntimeException exception) {
            return fallbackToMock(request, exception);
        }
    }

    private OpenAiCoachReplyResponse fallbackToMock(
            OpenAiCoachChatRequest request,
            RuntimeException exception
    ) {
        String fallbackReason = resolveFallbackReason(exception);
        log.warn(
                "COACH_LLM_FALLBACK_TO_MOCK jobId={} provider={} reason={}",
                request.jobId(),
                coachLlmProperties.isNvidiaProvider() ? "nvidia" : "openai",
                fallbackReason
        );
        return mockReply(request, fallbackReason);
    }

    private boolean canUseRealApi() {
        if (coachLlmProperties.isNvidiaProvider()) {
            return coachLlmProperties.hasNvidiaApiKey();
        }

        return openAiProperties.canUseRealApi();
    }

    private String resolveModel() {
        return coachLlmProperties.isNvidiaProvider()
                ? coachLlmProperties.getNvidiaModel()
                : openAiProperties.getModel();
    }

    private String currentMonthKey() {
        return YearMonth.now().toString();
    }

    private OpenAiCoachReplyResponse generateRealReply(OpenAiCoachChatRequest request) {
        List<ChatCompletionApiRequest.Message> messages = coachPromptBuilder.buildMessages(
                request.compactAnalysis(),
                request.historySummary(),
                request.history(),
                request.newUserMessage(),
                request.coachingProfile()
        );

        String model = resolveModel();
        ChatCompletionApiRequest apiRequest = new ChatCompletionApiRequest(
                model,
                messages,
                TEMPERATURE,
                MAX_TOKENS
        );

        ChatCompletionApiResponse apiResponse = coachLlmRestClient
                .post()
                .uri(CHAT_COMPLETIONS_PATH)
                .body(apiRequest)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (httpRequest, httpResponse) -> {
                            throw new IllegalStateException(
                                    "코치 LLM API 호출 실패: HTTP "
                                            + httpResponse.getStatusCode().value()
                            );
                        }
                )
                .body(ChatCompletionApiResponse.class);

        if (apiResponse == null) {
            throw new IllegalStateException("코치 LLM API 응답이 비어 있습니다.");
        }

        logUsage(request, model, apiResponse);

        String content = apiResponse.extractContent();

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("코치 LLM API 응답 텍스트가 비어 있습니다.");
        }

        return OpenAiCoachReplyResponse.real(request.jobId(), model, content);
    }

    private void logUsage(
            OpenAiCoachChatRequest request,
            String model,
            ChatCompletionApiResponse apiResponse
    ) {
        ChatCompletionApiResponse.Usage usage = apiResponse.usage();
        String provider = coachLlmProperties.isNvidiaProvider() ? "nvidia" : "openai";

        if (usage == null) {
            log.info(
                    "COACH_LLM_USAGE provider={} jobId={} model={} usage=none",
                    provider,
                    request.jobId(),
                    model
            );
            return;
        }

        log.info(
                "COACH_LLM_USAGE provider={} jobId={} model={} promptTokens={} completionTokens={} totalTokens={}",
                provider,
                request.jobId(),
                model,
                usage.prompt_tokens(),
                usage.completion_tokens(),
                usage.total_tokens()
        );
    }

    private OpenAiCoachReplyResponse mockReply(
            OpenAiCoachChatRequest request,
            String reason
    ) {
        return OpenAiCoachReplyResponse.mock(
                request.jobId(),
                resolveModel(),
                reason,
                MOCK_REPLY
        );
    }

    private String resolveMockReason() {
        if (coachLlmProperties.isNvidiaProvider()) {
            return coachLlmProperties.hasNvidiaApiKey() ? "mock mode" : "NVIDIA_API_KEY is empty";
        }

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
            return "코치 LLM HTTP client error: " + exception.getMessage();
        }

        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return exception.getMessage();
    }
}
