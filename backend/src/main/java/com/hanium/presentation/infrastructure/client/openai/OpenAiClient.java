package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final String RESPONSES_API_PATH = "/v1/responses";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final double NVIDIA_TEMPERATURE = 0.3;
    private static final int NVIDIA_MAX_TOKENS = 2000;

    private final OpenAiProperties openAiProperties;
    private final FeedbackLlmProperties feedbackLlmProperties;
    private final OpenAiPromptBuilder openAiPromptBuilder;
    private final RestClient openAiRestClient;
    private final RestClient feedbackLlmRestClient;
    private final ObjectMapper objectMapper;
    private final UserRateLimiter userRateLimiter;

    public OpenAiClient(
            OpenAiProperties openAiProperties,
            FeedbackLlmProperties feedbackLlmProperties,
            OpenAiPromptBuilder openAiPromptBuilder,
            RestClient openAiRestClient,
            @Qualifier("feedbackLlmRestClient") RestClient feedbackLlmRestClient,
            ObjectMapper objectMapper,
            UserRateLimiter userRateLimiter
    ) {
        this.openAiProperties = openAiProperties;
        this.feedbackLlmProperties = feedbackLlmProperties;
        this.openAiPromptBuilder = openAiPromptBuilder;
        this.openAiRestClient = openAiRestClient;
        this.feedbackLlmRestClient = feedbackLlmRestClient;
        this.objectMapper = objectMapper;
        this.userRateLimiter = userRateLimiter;
    }

    public OpenAiFeedbackResponse generateFeedback(OpenAiFeedbackRequest request) {
        if (canUseRealApi()) {
            // NVIDIA로 임시 대체 중일 때는 OpenAI 전용 월간 예산("openai-monthly")과 무관한
            // 호출이므로 그 버킷을 소비/검사하지 않는다.
            if (!feedbackLlmProperties.isNvidiaProvider()
                    && !userRateLimiter.tryConsume("openai-monthly", currentMonthKey())) {
                return generateMockFeedback(
                        request,
                        "MOCK",
                        "monthly OpenAI budget exceeded"
                );
            }

            try {
                return generateRealFeedback(request);
            } catch (RestClientException firstAttemptException) {
                // 컨테이너 기동 직후 첫 외부 HTTPS 호출이 콜드 DNS/TLS 협상으로 타임아웃되는
                // 경우를 코치 채팅에서 실제로 관찰했다(2026-07-23). 네트워크 오류에 한해
                // 한 번만 재시도한다.
                log.warn(
                        "FEEDBACK_LLM_RETRY_AFTER_NETWORK_ERROR jobId={} provider={} reason={}",
                        request.jobId(),
                        feedbackLlmProperties.isNvidiaProvider() ? "nvidia" : "openai",
                        firstAttemptException.getMessage()
                );
                try {
                    return generateRealFeedback(request);
                } catch (RuntimeException retryException) {
                    return fallbackToMockFeedback(request, retryException);
                }
            } catch (RuntimeException exception) {
                return fallbackToMockFeedback(request, exception);
            }
        }

        return generateMockFeedback(
                request,
                "MOCK",
                resolveMockReason()
        );
    }

    private OpenAiFeedbackResponse fallbackToMockFeedback(
            OpenAiFeedbackRequest request,
            RuntimeException exception
    ) {
        String fallbackReason = resolveFallbackReason(exception);
        log.warn(
                "FEEDBACK_LLM_FALLBACK_TO_MOCK jobId={} provider={} reason={}",
                request.jobId(),
                feedbackLlmProperties.isNvidiaProvider() ? "nvidia" : "openai",
                fallbackReason
        );
        return generateMockFeedback(request, "FALLBACK", fallbackReason);
    }

    private boolean canUseRealApi() {
        if (feedbackLlmProperties.isNvidiaProvider()) {
            return feedbackLlmProperties.hasNvidiaApiKey();
        }

        return openAiProperties.canUseRealApi();
    }

    private String resolveModel() {
        return feedbackLlmProperties.isNvidiaProvider()
                ? feedbackLlmProperties.getNvidiaModel()
                : openAiProperties.getModel();
    }

    private String currentMonthKey() {
        return YearMonth.now().toString();
    }

    private OpenAiFeedbackResponse generateRealFeedback(OpenAiFeedbackRequest request) {
        if (feedbackLlmProperties.isNvidiaProvider()) {
            return generateRealNvidiaFeedback(request);
        }

        return generateRealOpenAiFeedback(request);
    }

    private OpenAiFeedbackResponse generateRealOpenAiFeedback(OpenAiFeedbackRequest request) {
        OpenAiResponsesApiRequest apiRequest = createOpenAiResponsesApiRequest(request);

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

        return parseRealOpenAiFeedbackResponse(request.jobId(), outputText, openAiProperties.getModel());
    }

    // NVIDIA NIM(build.nvidia.com)의 vLLM 백엔드는 response_format=json_object를 스키마 없이
    // 보내면 400으로 거부하지만, 실제 스키마를 실은 response_format=json_schema(strict)는
    // grammar-constrained decoding으로 지원한다 - 프롬프트 지시만으로는(이전 방식) 실측
    // 21K 토큰 규모 프롬프트에서 배열 닫는 괄호 직전에 낙오 문자가 섞여 JSON 파싱이 4회 중
    // 2회 실패했지만, json_schema strict를 걸면 같은 조건에서 7회 연속 성공했다(2026-08-13
    // 실측, docs/service-plan 미기록 - 필요 시 재현 가능). 파싱은 기존
    // parseRealOpenAiFeedbackResponse()의 방어적 파싱을 그대로 재사용해, 스키마가 강제하지
    // 못하는 값(빈 문자열 등)까지 한 번 더 검사한다.
    private OpenAiFeedbackResponse generateRealNvidiaFeedback(OpenAiFeedbackRequest request) {
        String systemPrompt = openAiPromptBuilder.buildSystemPrompt();
        String userPrompt = openAiPromptBuilder.buildUserPrompt(request);
        String model = resolveModel();

        ChatCompletionApiRequest apiRequest = new ChatCompletionApiRequest(
                model,
                List.of(
                        ChatCompletionApiRequest.Message.system(systemPrompt),
                        ChatCompletionApiRequest.Message.user(userPrompt)
                ),
                NVIDIA_TEMPERATURE,
                NVIDIA_MAX_TOKENS,
                createFeedbackResponseFormat()
        );

        ChatCompletionApiResponse apiResponse = feedbackLlmRestClient
                .post()
                .uri(CHAT_COMPLETIONS_PATH)
                .body(apiRequest)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (httpRequest, httpResponse) -> {
                            throw new IllegalStateException(
                                    "피드백 LLM API 호출 실패: HTTP "
                                            + httpResponse.getStatusCode().value()
                            );
                        }
                )
                .body(ChatCompletionApiResponse.class);

        if (apiResponse == null) {
            throw new IllegalStateException("피드백 LLM API 응답이 비어 있습니다.");
        }

        String content = apiResponse.extractContent();

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("피드백 LLM API 응답 텍스트가 비어 있습니다.");
        }

        logNvidiaUsage(request, model, apiResponse);

        return parseRealOpenAiFeedbackResponse(request.jobId(), content, model);
    }

    // NVIDIA NIM(vLLM)의 response_format=json_schema에 실어 보낼 스키마입니다.
    // OpenAiPromptBuilder.buildSystemPrompt()가 지시하는 5개 필드 형태와 반드시 일치해야
    // 합니다 - 스키마와 프롬프트 지시가 어긋나면 모델이 어느 쪽을 따를지 보장할 수 없습니다.
    private Map<String, Object> createFeedbackResponseFormat() {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "feedback");
        jsonSchema.put("schema", createFeedbackJsonSchema());
        jsonSchema.put("strict", true);

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        return responseFormat;
    }

    private Map<String, Object> createFeedbackJsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("overall", createStringSchema());
        properties.put("strengths", createStringArraySchema());
        properties.put("improvements", createStringArraySchema());
        properties.put("practicePlan", createObjectArraySchema(
                List.of("title", "description", "duration")
        ));
        properties.put("timelineFeedback", createObjectArraySchema(
                List.of("category", "title", "summary", "recommendation")
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "overall", "strengths", "improvements", "practicePlan", "timelineFeedback"
        ));
        return schema;
    }

    private Map<String, Object> createStringSchema() {
        return Map.of("type", "string");
    }

    private Map<String, Object> createStringArraySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", createStringSchema());
        schema.put("minItems", 2);
        return schema;
    }

    private Map<String, Object> createObjectArraySchema(List<String> fieldNames) {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            itemProperties.put(fieldName, createStringSchema());
        }

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", fieldNames);

        Map<String, Object> arraySchema = new LinkedHashMap<>();
        arraySchema.put("type", "array");
        arraySchema.put("items", itemSchema);
        arraySchema.put("minItems", 2);
        return arraySchema;
    }

    private void logNvidiaUsage(
            OpenAiFeedbackRequest request,
            String model,
            ChatCompletionApiResponse apiResponse
    ) {
        ChatCompletionApiResponse.Usage usage = apiResponse.usage();

        if (usage == null) {
            log.info("FEEDBACK_LLM_USAGE provider=nvidia jobId={} model={} usage=none", request.jobId(), model);
            return;
        }

        log.info(
                "FEEDBACK_LLM_USAGE provider=nvidia jobId={} model={} promptTokens={} completionTokens={} totalTokens={}",
                request.jobId(),
                model,
                usage.prompt_tokens(),
                usage.completion_tokens(),
                usage.total_tokens()
        );
    }

    private void logOpenAiUsage(
            OpenAiFeedbackRequest request,
            OpenAiResponsesApiResponse apiResponse
    ) {
        OpenAiResponsesApiResponse.Usage usage = apiResponse.usage();

        if (usage == null) {
            log.info(
                    "OPENAI_USAGE jobId={} model={} usage=none",
                    request.jobId(),
                    openAiProperties.getModel()
            );
            return;
        }

        log.info(
                "OPENAI_USAGE jobId={} model={} inputTokens={} outputTokens={} totalTokens={}",
                request.jobId(),
                openAiProperties.getModel(),
                usage.input_tokens(),
                usage.output_tokens(),
                usage.total_tokens()
        );
    }

    private OpenAiResponsesApiRequest createOpenAiResponsesApiRequest(
            OpenAiFeedbackRequest request
    ) {
        String systemPrompt = openAiPromptBuilder.buildSystemPrompt();
        String userPrompt = openAiPromptBuilder.buildUserPrompt(request);

        return OpenAiResponsesApiRequest.create(
                openAiProperties.getModel(),
                systemPrompt,
                userPrompt
        );
    }

    private OpenAiFeedbackResponse parseRealOpenAiFeedbackResponse(
            String jobId,
            String outputText,
            String model
    ) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    outputText,
                    new TypeReference<>() {
                    }
            );

            String overall = getString(parsed, "overall");
            List<String> strengths = getStringList(parsed, "strengths");
            List<String> improvements = getStringList(parsed, "improvements");
            List<Map<String, Object>> practicePlan = getMapList(parsed, "practicePlan");
            List<Map<String, Object>> timelineFeedback = getMapList(parsed, "timelineFeedback");

            // 실제 OpenAI(json_schema strict)는 필드 누락이 사실상 없지만, NVIDIA의
            // json_object 모드는 스키마를 강제하지 않아 필드를 통째로 생략하는 경우를
            // 실제로 관찰했다(2026-07-23) - overall만 채우고 나머지는 빈 응답. 이를 그대로
            // 사용자에게 보여주면 결과 페이지가 반쪽짜리가 되므로, 필수 필드가 비어 있으면
            // 실패로 간주해 (호출부의) 재시도/mock 폴백 경로를 타게 한다.
            if (overall.isBlank()
                    || strengths.isEmpty()
                    || improvements.isEmpty()
                    || practicePlan.isEmpty()
                    || timelineFeedback.isEmpty()) {
                throw new IllegalStateException(
                        "LLM 응답에 필수 필드가 비어 있습니다(overall/strengths/improvements/practicePlan/timelineFeedback)."
                );
            }

            return OpenAiFeedbackResponse.real(
                    jobId,
                    model,
                    overall,
                    strengths,
                    improvements,
                    practicePlan,
                    timelineFeedback
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LLM 응답 JSON 파싱에 실패했습니다.", exception);
        }
    }

    private OpenAiFeedbackResponse generateMockFeedback(
            OpenAiFeedbackRequest request,
            String generationMode,
            String fallbackReason
    ) {
        Map<String, Object> compactAnalysis = nullSafeMap(request.compactAnalysis());

        Map<String, Object> modelInputs = nullSafeMap(compactAnalysis.get("modelInputs"));

        Map<String, Object> scoreSummary = nullSafeMap(modelInputs.get("scoreSummary"));
        Map<String, Object> speechSummary = nullSafeMap(modelInputs.get("speechSummary"));
        Map<String, Object> visualSummary = nullSafeMap(modelInputs.get("visualSummary"));
        Map<String, Object> transcriptSummary = nullSafeMap(modelInputs.get("transcriptSummary"));
        Map<String, Object> feedbackFocus = nullSafeMap(modelInputs.get("feedbackFocus"));

        int totalScore = getInt(scoreSummary, "totalScore");
        int postureScore = getInt(scoreSummary, "postureScore");
        int gazeScore = getInt(scoreSummary, "gazeScore");
        int speechScore = getInt(scoreSummary, "speechScore");
        int gestureScore = getInt(scoreSummary, "gestureScore");
        int expressionScore = getInt(scoreSummary, "expressionScore");

        List<String> strengths = createStrengths(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                expressionScore
        );

        List<String> improvements = createImprovements(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                expressionScore,
                speechSummary,
                visualSummary,
                transcriptSummary
        );

        String overallFeedback = createOverallFeedback(
                totalScore,
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                expressionScore,
                feedbackFocus,
                strengths,
                improvements
        );

        List<Map<String, Object>> practicePlan = createPracticePlan(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                expressionScore,
                speechSummary
        );

        List<Map<String, Object>> timelineFeedback = createTimelineFeedback(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                expressionScore,
                speechSummary,
                visualSummary
        );

        if ("FALLBACK".equals(generationMode)) {
            return OpenAiFeedbackResponse.fallback(
                    request.jobId(),
                    resolveModel(),
                    fallbackReason,
                    overallFeedback,
                    strengths,
                    improvements,
                    practicePlan,
                    timelineFeedback
            );
        }

        return OpenAiFeedbackResponse.mock(
                request.jobId(),
                resolveModel(),
                fallbackReason,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    private String resolveMockReason() {
        if (feedbackLlmProperties.isNvidiaProvider()) {
            return feedbackLlmProperties.hasNvidiaApiKey() ? "mock mode" : "NVIDIA_API_KEY is empty";
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
            return "LLM HTTP client error: " + exception.getMessage();
        }

        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return exception.getMessage();
    }

    private List<String> createStrengths(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore
    ) {
        List<String> strengths = new ArrayList<>();

        if (postureScore >= 75) {
            strengths.add("자세가 비교적 안정적으로 유지되어 발표자의 신뢰감이 잘 전달됩니다.");
        }

        if (gazeScore >= 75) {
            strengths.add("시선 처리가 안정적이어서 청중 또는 카메라와의 연결감이 좋습니다.");
        }

        if (speechScore >= 75) {
            strengths.add("말하기 속도, 침묵 흐름, 음량 안정성이 비교적 안정적이어서 내용 전달이 자연스럽습니다.");
        }

        if (gestureScore >= 75) {
            strengths.add("제스처 사용이 발표 흐름에 적절히 반영되어 전달력을 높여줍니다.");
        }

        if (expressionScore >= 75) {
            strengths.add("표정과 발표 몰입도가 비교적 잘 드러나 발표가 생동감 있게 보입니다.");
        }

        if (strengths.isEmpty()) {
            strengths.add("분석 가능한 영상·음성 데이터가 수집되어 발표 개선 방향을 구체적으로 확인할 수 있습니다.");
        }

        return strengths;
    }

    private List<String> createImprovements(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore,
            Map<String, Object> speechSummary,
            Map<String, Object> visualSummary,
            Map<String, Object> transcriptSummary
    ) {
        List<String> improvements = new ArrayList<>();

        if (postureScore < 70) {
            improvements.add(
                    "자세 안정성이 다소 부족합니다. 발표 중 몸이 화면 중앙에서 벗어나지 않도록 정면 위치를 유지하고, 좌우 어깨 높이가 크게 흔들리지 않도록 연습하는 것이 좋습니다."
                            + createOptionalMetricText(" 자세 검출률", getDouble(visualSummary, "poseDetectionRate"), true)
                            + createOptionalMetricText(" 평균 어깨 차이", getDouble(visualSummary, "averageShoulderDiff"), false)
            );
        }

        if (gazeScore < 70) {
            improvements.add(
                    "시선 처리가 불안정하게 분석되었습니다. 핵심 문장을 말할 때 카메라 또는 청중 방향을 2~3초 이상 유지하는 연습이 필요합니다."
                            + createOptionalMetricText(" 얼굴 검출률", getDouble(visualSummary, "faceDetectionRate"), true)
                            + createOptionalText(" 아이컨택 수준", visualSummary.get("eyeContactLevel"))
            );
        }

        if (speechScore < 70) {
            improvements.add(
                    "음성 흐름 개선이 필요합니다. 말하기 속도가 너무 빠르거나 느리면 전달력이 떨어질 수 있고, 긴 침묵이 반복되면 발표 흐름이 끊겨 보일 수 있습니다."
                            + createOptionalNumberText(" WPM", getInt(speechSummary, "speechSpeedWpm"))
                            + createOptionalNumberText(" 침묵 횟수", getInt(speechSummary, "silenceCount"))
                            + createOptionalMetricText(" 침묵 비율", getDouble(speechSummary, "silenceRatio"), true)
                            + createOptionalNumberText(" 음량 안정성", getInt(speechSummary, "volumeStabilityScore"))
            );
        }

        if (getInt(speechSummary, "volumeStabilityScore") < 70
                && Boolean.TRUE.equals(speechSummary.get("volumeStabilityImplemented"))) {
            improvements.add(
                    "음량 변화 폭이 커서 문장별 전달력이 고르지 않게 들릴 수 있습니다. 마이크와의 거리를 일정하게 유지하고, 강조 구간에서도 갑자기 크게 말하기보다 속도와 호흡으로 강약을 조절하세요."
                            + createOptionalNumberText(" 음량 안정성", getInt(speechSummary, "volumeStabilityScore"))
                            + createOptionalMetricText(" RMS dB 표준편차", getDouble(speechSummary, "volumeRmsDbStdDev"), false)
            );
        }

        if (getInt(speechSummary, "fillerScore") < 70 || getInt(speechSummary, "fillerCount") > 0) {
            improvements.add(
                    "필러 표현 사용을 줄이면 발표가 더 명확해집니다. '음', '어', '그', '이제' 같은 표현이 나오는 구간에서는 잠시 멈추고 다음 문장을 또렷하게 시작하는 방식으로 연습하세요."
                            + createOptionalNumberText(" 필러 수", getInt(speechSummary, "fillerCount"))
                            + createOptionalMetricText(" 필러 비율", getDouble(speechSummary, "fillerRatio"), true)
            );
        }

        if (gestureScore < 70) {
            improvements.add(
                    "제스처 사용이 부족하거나 불안정하게 감지되었습니다. 중요한 키워드를 말할 때 손동작을 한 번씩 사용하는 방식으로 자연스러운 제스처 루틴을 만드는 것이 좋습니다."
                            + createOptionalMetricText(" 제스처 비율", getDouble(visualSummary, "gestureRate"), true)
                            + createOptionalMetricText(" 손 검출률", getDouble(visualSummary, "handVisibilityRate"), true)
            );
        }

        if (expressionScore < 70) {
            improvements.add(
                    "표정 변화와 발표 몰입감이 다소 약하게 분석되었습니다. 문장 끝에서 미세한 미소, 고개 끄덕임, 눈 뜸 변화를 더하면 발표가 덜 단조롭게 보입니다."
                            + createOptionalText(" 주요 표정", visualSummary.get("dominantEmotion"))
                            + createOptionalMetricText(" 표정 점수", getDouble(visualSummary, "expressionScore"), false)
            );
        }

        Map<String, Object> contentStructure = nullSafeMap(transcriptSummary.get("contentStructure"));
        if (getBoolean(transcriptSummary, "sttSuccess")
                && "needs_structure_markers".equals(contentStructure.get("structureHint"))) {
            improvements.add(
                    "발표 내용의 구조 표지가 부족하게 감지되었습니다. '먼저', '다음으로', '결론적으로'처럼 흐름을 알려주는 표현을 넣으면 청중이 핵심 메시지를 따라가기 쉬워집니다."
                            + createOptionalNumberText(" 문장 수", getInt(contentStructure, "sentenceCount"))
                            + " 구조 표지: " + getInt(contentStructure, "transitionMarkerCount") + "."
            );
        }

        if (improvements.isEmpty()) {
            improvements.add("큰 약점은 보이지 않습니다. 다음 단계에서는 발표 내용의 논리 구조와 핵심 메시지 전달력을 중심으로 개선하면 좋습니다.");
        }

        return improvements;
    }

    private String createOverallFeedback(
            int totalScore,
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore,
            Map<String, Object> feedbackFocus,
            List<String> strengths,
            List<String> improvements
    ) {
        String levelText = resolveLevelText(totalScore);

        String strongestArea = translateArea(String.valueOf(feedbackFocus.getOrDefault(
                "strongestArea",
                resolveStrongestArea(postureScore, gazeScore, speechScore, gestureScore, expressionScore)
        )));

        String weakestArea = translateArea(String.valueOf(feedbackFocus.getOrDefault(
                "weakestArea",
                resolveWeakestArea(postureScore, gazeScore, speechScore, gestureScore, expressionScore)
        )));

        return "이번 발표의 종합 점수는 "
                + totalScore
                + "점으로, 전체 수준은 "
                + levelText
                + "입니다. 가장 강하게 나타난 영역은 "
                + strongestArea
                + "이며, 우선적으로 보완하면 좋은 영역은 "
                + weakestArea
                + "입니다. "
                + "현재 피드백은 LLM 입력용으로 정리된 rawMetrics와 modelInputs를 기반으로 생성된 Mock 응답입니다. "
                + strengths.get(0)
                + " 반면, "
                + improvements.get(0);
    }

    private List<Map<String, Object>> createPracticePlan(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore,
            Map<String, Object> speechSummary
    ) {
        List<Map<String, Object>> practicePlan = new ArrayList<>();

        if (speechScore < 75) {
            practicePlan.add(createPracticeItem(
                    "음성 흐름 안정화",
                    "발표 원고를 1분 단위로 끊어 읽으면서 WPM과 침묵 구간을 확인합니다. 너무 빠른 구간은 문장 사이에 짧은 호흡을 넣고, 너무 느린 구간은 핵심 단어를 중심으로 문장을 압축하세요.",
                    "10분"
            ));
        }

        if (getInt(speechSummary, "fillerCount") > 0) {
            practicePlan.add(createPracticeItem(
                    "필러 표현 줄이기",
                    "녹음 후 '음', '어', '그', '이제' 같은 표현이 나온 문장을 표시하고, 해당 위치에서 1초 멈춘 뒤 다음 문장을 시작하는 방식으로 다시 연습합니다.",
                    "8분"
            ));
        }

        if (postureScore < 75) {
            practicePlan.add(createPracticeItem(
                    "자세 고정 연습",
                    "카메라 중앙에 상반신이 안정적으로 들어오도록 위치를 맞추고, 발표 중 어깨 높이와 고개 기울기가 크게 변하지 않도록 2분 발표를 반복합니다.",
                    "7분"
            ));
        }

        if (gazeScore < 75) {
            practicePlan.add(createPracticeItem(
                    "시선 유지 연습",
                    "핵심 문장을 말할 때 카메라를 2~3초간 바라보는 연습을 합니다. 원고를 보는 시간과 카메라를 보는 시간을 분리하는 것이 좋습니다.",
                    "7분"
            ));
        }

        if (gestureScore < 75) {
            practicePlan.add(createPracticeItem(
                    "제스처 루틴 만들기",
                    "중요한 키워드, 숫자, 전환 문장을 말할 때 손동작을 한 번씩 넣는 방식으로 제스처 위치를 미리 정해 연습합니다.",
                    "8분"
            ));
        }

        if (expressionScore < 75) {
            practicePlan.add(createPracticeItem(
                    "표정 변화 연습",
                    "도입부, 강조 문장, 마무리 문장에서 미세한 미소와 고개 끄덕임을 넣어 발표의 생동감을 높입니다.",
                    "6분"
            ));
        }

        if (practicePlan.isEmpty()) {
            practicePlan.add(createPracticeItem(
                    "실전 발표 리허설",
                    "현재 발표 흐름은 전반적으로 안정적입니다. 실제 발표 환경과 비슷하게 서서 리허설하고, 시간 제한 안에 마무리하는 연습을 진행하세요.",
                    "15분"
            ));
        }

        return practicePlan;
    }

    private List<Map<String, Object>> createTimelineFeedback(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore,
            Map<String, Object> speechSummary,
            Map<String, Object> visualSummary
    ) {
        List<Map<String, Object>> timelineFeedback = new ArrayList<>();

        timelineFeedback.add(createTimelineItem(
                "speech",
                "음성 흐름",
                "음성 점수는 " + speechScore + "점입니다. WPM "
                        + getInt(speechSummary, "speechSpeedWpm")
                        + ", 침묵 횟수 "
                        + getInt(speechSummary, "silenceCount")
                        + "회, 필러 수 "
                        + getInt(speechSummary, "fillerCount")
                        + "개, 음량 안정성 "
                        + getInt(speechSummary, "volumeStabilityScore")
                        + "점이 확인되었습니다.",
                "발표 초반에는 속도를 안정적으로 잡고, 중간 이후에는 문장 사이 호흡을 일정하게 유지하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "posture",
                "자세 안정성",
                "자세 점수는 " + postureScore + "점입니다. 자세 검출률은 "
                        + formatPercent(getDouble(visualSummary, "poseDetectionRate"))
                        + "입니다.",
                "카메라 중앙에 몸을 유지하고, 말하는 동안 어깨와 고개 움직임이 과하게 흔들리지 않도록 조정하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "gaze",
                "시선 처리",
                "시선 점수는 " + gazeScore + "점입니다. 얼굴 검출률은 "
                        + formatPercent(getDouble(visualSummary, "faceDetectionRate"))
                        + "입니다.",
                "핵심 문장을 말할 때는 원고보다 카메라 또는 청중 방향을 우선해 시선을 유지하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "gesture",
                "제스처 사용",
                "제스처 점수는 " + gestureScore + "점입니다. 제스처 비율은 "
                        + formatPercent(getDouble(visualSummary, "gestureRate"))
                        + "입니다.",
                "중요한 내용 전환 지점마다 손동작을 넣으면 발표의 구조가 더 명확하게 보입니다."
        ));

        timelineFeedback.add(createTimelineItem(
                "expression",
                "표정과 몰입감",
                "표정 점수는 " + expressionScore + "점입니다. 주요 표정 상태는 "
                        + visualSummary.getOrDefault("dominantEmotion", "unknown")
                        + "입니다.",
                "강조 문장과 결론 부분에서 표정 변화를 주면 발표의 설득력이 더 높아집니다."
        ));

        return timelineFeedback;
    }

    private Map<String, Object> createPracticeItem(
            String title,
            String description,
            String duration
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("description", description);
        item.put("duration", duration);
        return item;
    }

    private Map<String, Object> createTimelineItem(
            String category,
            String title,
            String summary,
            String recommendation
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("title", title);
        item.put("summary", summary);
        item.put("recommendation", recommendation);
        return item;
    }

    private String resolveLevelText(int totalScore) {
        if (totalScore >= 85) {
            return "우수";
        }

        if (totalScore >= 70) {
            return "양호";
        }

        if (totalScore >= 50) {
            return "보통";
        }

        return "개선 필요";
    }

    private String resolveStrongestArea(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore
    ) {
        int maxScore = postureScore;
        String area = "posture";

        if (gazeScore > maxScore) {
            maxScore = gazeScore;
            area = "gaze";
        }

        if (speechScore > maxScore) {
            maxScore = speechScore;
            area = "speech";
        }

        if (gestureScore > maxScore) {
            maxScore = gestureScore;
            area = "gesture";
        }

        if (expressionScore > maxScore) {
            area = "expression";
        }

        return area;
    }

    private String resolveWeakestArea(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int expressionScore
    ) {
        int minScore = postureScore;
        String area = "posture";

        if (gazeScore < minScore) {
            minScore = gazeScore;
            area = "gaze";
        }

        if (speechScore < minScore) {
            minScore = speechScore;
            area = "speech";
        }

        if (gestureScore < minScore) {
            minScore = gestureScore;
            area = "gesture";
        }

        if (expressionScore < minScore) {
            area = "expression";
        }

        return area;
    }

    private String translateArea(String area) {
        return switch (area) {
            case "posture" -> "자세";
            case "gaze" -> "시선";
            case "speech" -> "음성";
            case "gesture" -> "제스처";
            case "expression" -> "표정";
            case "content_structure" -> "내용 구성";
            default -> area;
        };
    }

    private String createOptionalMetricText(
            String label,
            double value,
            boolean percent
    ) {
        if (value <= 0) {
            return "";
        }

        if (percent) {
            return label + ": " + formatPercent(value) + ".";
        }

        return label + ": " + value + ".";
    }

    private String createOptionalNumberText(
            String label,
            int value
    ) {
        if (value <= 0) {
            return "";
        }

        return label + ": " + value + ".";
    }

    private String createOptionalText(
            String label,
            Object value
    ) {
        if (value == null) {
            return "";
        }

        return label + ": " + value + ".";
    }

    private String formatPercent(double value) {
        return Math.round(value * 100) + "%";
    }

    private Map<String, Object> nullSafeMap(Object value) {
        return JsonMapSupport.copyStringKeyedMap(value);
    }

    private String getString(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> getStringList(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }

        return List.of();
    }

    private List<Map<String, Object>> getMapList(
            Map<String, Object> map,
            String key
    ) {
        return JsonMapSupport.copyStringKeyedMapList(map.get(key));
    }

    private int getInt(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }

    private double getDouble(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }

    private boolean getBoolean(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }

        return false;
    }
}
