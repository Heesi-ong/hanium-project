package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiResponsesApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final String RESPONSES_API_PATH = "/v1/responses";

    private final OpenAiProperties openAiProperties;
    private final OpenAiPromptBuilder openAiPromptBuilder;
    private final RestClient openAiRestClient;
    private final ObjectMapper objectMapper;

    public OpenAiClient(
            OpenAiProperties openAiProperties,
            OpenAiPromptBuilder openAiPromptBuilder,
            RestClient openAiRestClient,
            ObjectMapper objectMapper
    ) {
        this.openAiProperties = openAiProperties;
        this.openAiPromptBuilder = openAiPromptBuilder;
        this.openAiRestClient = openAiRestClient;
        this.objectMapper = objectMapper;
    }

    public OpenAiFeedbackResponse generateFeedback(OpenAiFeedbackRequest request) {
        if (openAiProperties.canUseRealApi()) {
            try {
                return generateRealOpenAiFeedback(request);
            } catch (RuntimeException exception) {
                return generateMockFeedback(
                        request,
                        "FALLBACK",
                        resolveFallbackReason(exception)
                );
            }
        }

        return generateMockFeedback(
                request,
                "MOCK",
                resolveMockReason()
        );
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

        return parseRealOpenAiFeedbackResponse(request.jobId(), outputText);
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
            String outputText
    ) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    outputText,
                    new TypeReference<>() {
                    }
            );

            return OpenAiFeedbackResponse.real(
                    jobId,
                    openAiProperties.getModel(),
                    getString(parsed, "overall"),
                    getStringList(parsed, "strengths"),
                    getStringList(parsed, "improvements"),
                    getMapList(parsed, "practicePlan"),
                    getMapList(parsed, "timelineFeedback")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenAI 응답 JSON 파싱에 실패했습니다.", exception);
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
                    openAiProperties.getModel(),
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
                openAiProperties.getModel(),
                fallbackReason,
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
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
            strengths.add("말하기 속도와 침묵 흐름이 비교적 안정적이어서 내용 전달이 자연스럽습니다.");
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

        if (getBoolean(transcriptSummary, "sttSuccess")) {
            improvements.add("STT transcript는 현재 LLM 입력용 원문 데이터로만 사용됩니다. 발표 내용 구조 분석은 추후 LLM 또는 별도 분석 단계에서 처리하는 것이 좋습니다.");
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
                        + "개가 확인되었습니다.",
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> nullSafeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Map.of();
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMapList(
            Map<String, Object> map,
            String key
    ) {
        Object value = map.get(key);

        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }

        return List.of();
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
