package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient {

    public OpenAiFeedbackResponse generateFeedback(OpenAiFeedbackRequest request) {
        Map<String, Object> compactAnalysis = nullSafeMap(request.compactAnalysis());

        Map<String, Object> scoreSummary = nullSafeMap(compactAnalysis.get("scoreSummary"));
        Map<String, Object> score = extractScoreMap(scoreSummary);

        Map<String, Object> audio = nullSafeMap(scoreSummary.get("audio"));
        Map<String, Object> filler = nullSafeMap(scoreSummary.get("filler"));

        Map<String, Object> visualSummary = nullSafeMap(compactAnalysis.get("visualSummary"));
        Map<String, Object> pose = nullSafeMap(visualSummary.get("pose"));
        Map<String, Object> gesture = nullSafeMap(visualSummary.get("gesture"));
        Map<String, Object> face = nullSafeMap(visualSummary.get("face"));
        Map<String, Object> emotion = nullSafeMap(visualSummary.get("emotion"));

        int totalScore = getInt(score, "totalScore");
        int postureScore = getInt(score, "postureScore");
        int gazeScore = getInt(score, "gazeScore");
        int speechScore = getInt(score, "speechScore");
        int gestureScore = getInt(score, "gestureScore");
        int emotionScore = getInt(score, "emotionScore");

        List<String> strengths = createStrengths(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        );

        List<String> improvements = createImprovements(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore,
                audio,
                filler,
                pose,
                gesture,
                face,
                emotion
        );

        String overallFeedback = createOverallFeedback(
                totalScore,
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore,
                strengths,
                improvements
        );

        List<Map<String, Object>> practicePlan = createPracticePlan(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore,
                audio,
                filler
        );

        List<Map<String, Object>> timelineFeedback = createTimelineFeedback(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore,
                audio,
                filler,
                pose,
                gesture,
                face,
                emotion
        );

        return new OpenAiFeedbackResponse(
                request.jobId(),
                overallFeedback,
                strengths,
                improvements,
                practicePlan,
                timelineFeedback
        );
    }

    private Map<String, Object> extractScoreMap(Map<String, Object> scoreSummary) {
        Map<String, Object> nestedScore = nullSafeMap(scoreSummary.get("score"));

        if (!nestedScore.isEmpty()) {
            return nestedScore;
        }

        return scoreSummary;
    }

    private List<String> createStrengths(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore
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

        if (emotionScore >= 75) {
            strengths.add("표정과 발표 몰입도가 비교적 잘 드러나 발표가 생동감 있게 보입니다.");
        }

        if (strengths.isEmpty()) {
            strengths.add("전체 분석 항목이 정상적으로 수집되어 발표 개선 방향을 구체적으로 확인할 수 있습니다.");
        }

        return strengths;
    }

    private List<String> createImprovements(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore,
            Map<String, Object> audio,
            Map<String, Object> filler,
            Map<String, Object> pose,
            Map<String, Object> gesture,
            Map<String, Object> face,
            Map<String, Object> emotion
    ) {
        List<String> improvements = new ArrayList<>();

        if (postureScore < 70) {
            double detectionRate = getDouble(pose, "detectionRate");
            double shoulderDiff = getDouble(pose, "averageShoulderDiff");

            improvements.add(
                    "자세 안정성이 다소 부족합니다. 발표 중 몸이 화면에서 벗어나지 않도록 정면 위치를 유지하고, 좌우 어깨 높이가 크게 흔들리지 않도록 연습하는 것이 좋습니다."
                            + createOptionalMetricText(" 자세 검출률", detectionRate, true)
                            + createOptionalMetricText(" 평균 어깨 차이", shoulderDiff, false)
            );
        }

        if (gazeScore < 70) {
            double faceDetectionRate = getDouble(face, "detectionRate");
            Object eyeContactLevel = face.get("eyeContactLevel");

            improvements.add(
                    "시선 처리가 불안정하게 분석되었습니다. 발표 중 핵심 문장을 말할 때 카메라 또는 청중 방향을 2~3초 이상 유지하는 연습이 필요합니다."
                            + createOptionalMetricText(" 얼굴 검출률", faceDetectionRate, true)
                            + createOptionalText(" 아이컨택 수준", eyeContactLevel)
            );
        }

        if (speechScore < 70) {
            int wpm = getInt(audio, "speechSpeedWpm");
            int silenceCount = getInt(audio, "silenceCount");
            double silenceRatio = getDouble(audio, "silenceRatio");

            improvements.add(
                    "음성 흐름 개선이 필요합니다. 말하기 속도가 너무 빠르거나 느리면 전달력이 떨어질 수 있고, 긴 침묵이 반복되면 발표 흐름이 끊겨 보일 수 있습니다."
                            + createOptionalNumberText(" WPM", wpm)
                            + createOptionalNumberText(" 침묵 횟수", silenceCount)
                            + createOptionalMetricText(" 침묵 비율", silenceRatio, true)
            );
        }

        if (getInt(filler, "fillerScore") < 70 || getInt(filler, "fillerCount") > 0) {
            int fillerCount = getInt(filler, "fillerCount");
            double fillerRatio = getDouble(filler, "fillerRatio");

            improvements.add(
                    "필러 표현 사용을 줄이면 발표가 더 명확해집니다. '음', '어', '그', '이제' 같은 표현이 나오는 구간에서는 잠시 멈추고 다음 문장을 또렷하게 시작하는 방식으로 연습하세요."
                            + createOptionalNumberText(" 필러 수", fillerCount)
                            + createOptionalMetricText(" 필러 비율", fillerRatio, true)
            );
        }

        if (gestureScore < 70) {
            double gestureRate = getDouble(gesture, "gestureRate");
            double handVisibilityRate = getDouble(gesture, "handVisibilityRate");

            improvements.add(
                    "제스처 사용이 부족하거나 불안정하게 감지되었습니다. 중요한 키워드를 말할 때 손동작을 한 번씩 사용하는 방식으로 자연스러운 제스처 루틴을 만드는 것이 좋습니다."
                            + createOptionalMetricText(" 제스처 비율", gestureRate, true)
                            + createOptionalMetricText(" 손 검출률", handVisibilityRate, true)
            );
        }

        if (emotionScore < 70) {
            Object dominantEmotion = emotion.get("dominantEmotion");
            double detectionRate = getDouble(emotion, "detectionRate");

            improvements.add(
                    "표정 변화와 발표 몰입감이 다소 약하게 분석되었습니다. 문장 끝에서 미세한 미소, 고개 끄덕임, 눈 뜸 변화를 더하면 발표가 덜 단조롭게 보입니다."
                            + createOptionalText(" 주요 표정", dominantEmotion)
                            + createOptionalMetricText(" 표정 검출률", detectionRate, true)
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
            int emotionScore,
            List<String> strengths,
            List<String> improvements
    ) {
        String levelText = resolveLevelText(totalScore);
        String strongestArea = resolveStrongestArea(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        );
        String weakestArea = resolveWeakestArea(
                postureScore,
                gazeScore,
                speechScore,
                gestureScore,
                emotionScore
        );

        return "이번 발표의 종합 점수는 "
                + totalScore
                + "점으로, 전체 수준은 "
                + levelText
                + "입니다. 가장 강하게 나타난 영역은 "
                + strongestArea
                + "이며, 우선적으로 보완하면 좋은 영역은 "
                + weakestArea
                + "입니다. "
                + "현재 분석 결과 기준으로는 "
                + strengths.get(0)
                + " 반면, "
                + improvements.get(0);
    }

    private List<Map<String, Object>> createPracticePlan(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore,
            Map<String, Object> audio,
            Map<String, Object> filler
    ) {
        List<Map<String, Object>> practicePlan = new ArrayList<>();

        if (speechScore < 75) {
            practicePlan.add(createPracticeItem(
                    "음성 흐름 안정화",
                    "발표 원고를 1분 단위로 끊어 읽으면서 WPM과 침묵 구간을 확인합니다. 너무 빠른 구간은 문장 사이에 짧은 호흡을 넣고, 너무 느린 구간은 핵심 단어를 중심으로 문장을 압축하세요.",
                    "10분"
            ));
        }

        if (getInt(filler, "fillerCount") > 0) {
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

        if (emotionScore < 75) {
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
            int emotionScore,
            Map<String, Object> audio,
            Map<String, Object> filler,
            Map<String, Object> pose,
            Map<String, Object> gesture,
            Map<String, Object> face,
            Map<String, Object> emotion
    ) {
        List<Map<String, Object>> timelineFeedback = new ArrayList<>();

        timelineFeedback.add(createTimelineItem(
                "speech",
                "음성 흐름",
                createSpeechTimelineSummary(speechScore, audio, filler),
                "발표 초반에는 속도를 안정적으로 잡고, 중간 이후에는 문장 사이 호흡을 일정하게 유지하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "posture",
                "자세 안정성",
                createPostureTimelineSummary(postureScore, pose),
                "카메라 중앙에 몸을 유지하고, 말하는 동안 어깨와 고개 움직임이 과하게 흔들리지 않도록 조정하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "gaze",
                "시선 처리",
                createGazeTimelineSummary(gazeScore, face),
                "핵심 문장을 말할 때는 원고보다 카메라 또는 청중 방향을 우선해 시선을 유지하세요."
        ));

        timelineFeedback.add(createTimelineItem(
                "gesture",
                "제스처 사용",
                createGestureTimelineSummary(gestureScore, gesture),
                "중요한 내용 전환 지점마다 손동작을 넣으면 발표의 구조가 더 명확하게 보입니다."
        ));

        timelineFeedback.add(createTimelineItem(
                "emotion",
                "표정과 몰입감",
                createEmotionTimelineSummary(emotionScore, emotion),
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

    private String createSpeechTimelineSummary(
            int speechScore,
            Map<String, Object> audio,
            Map<String, Object> filler
    ) {
        int wpm = getInt(audio, "speechSpeedWpm");
        int silenceCount = getInt(audio, "silenceCount");
        int fillerCount = getInt(filler, "fillerCount");

        if (speechScore >= 75 && fillerCount == 0) {
            return "말하기 속도와 침묵 흐름이 안정적이며, 필러 표현도 적게 감지되었습니다.";
        }

        return "음성 점수는 "
                + speechScore
                + "점입니다. WPM "
                + wpm
                + ", 침묵 횟수 "
                + silenceCount
                + "회, 필러 수 "
                + fillerCount
                + "개가 확인되었습니다.";
    }

    private String createPostureTimelineSummary(
            int postureScore,
            Map<String, Object> pose
    ) {
        double detectionRate = getDouble(pose, "detectionRate");
        double shoulderDiff = getDouble(pose, "averageShoulderDiff");

        return "자세 점수는 "
                + postureScore
                + "점입니다. 자세 검출률은 "
                + formatPercent(detectionRate)
                + "이며, 평균 어깨 차이는 "
                + shoulderDiff
                + "입니다.";
    }

    private String createGazeTimelineSummary(
            int gazeScore,
            Map<String, Object> face
    ) {
        double detectionRate = getDouble(face, "detectionRate");
        Object eyeContactLevel = face.get("eyeContactLevel");

        return "시선 점수는 "
                + gazeScore
                + "점입니다. 얼굴 검출률은 "
                + formatPercent(detectionRate)
                + "이며, 아이컨택 수준은 "
                + nullToDash(eyeContactLevel)
                + "입니다.";
    }

    private String createGestureTimelineSummary(
            int gestureScore,
            Map<String, Object> gesture
    ) {
        double gestureRate = getDouble(gesture, "gestureRate");
        double handVisibilityRate = getDouble(gesture, "handVisibilityRate");

        return "제스처 점수는 "
                + gestureScore
                + "점입니다. 제스처 비율은 "
                + formatPercent(gestureRate)
                + ", 손 검출률은 "
                + formatPercent(handVisibilityRate)
                + "입니다.";
    }

    private String createEmotionTimelineSummary(
            int emotionScore,
            Map<String, Object> emotion
    ) {
        Object dominantEmotion = emotion.get("dominantEmotion");
        double detectionRate = getDouble(emotion, "detectionRate");

        return "표정 점수는 "
                + emotionScore
                + "점입니다. 주요 표정 상태는 "
                + nullToDash(dominantEmotion)
                + "이며, 표정 검출률은 "
                + formatPercent(detectionRate)
                + "입니다.";
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
            int emotionScore
    ) {
        int maxScore = postureScore;
        String area = "자세";

        if (gazeScore > maxScore) {
            maxScore = gazeScore;
            area = "시선";
        }

        if (speechScore > maxScore) {
            maxScore = speechScore;
            area = "음성";
        }

        if (gestureScore > maxScore) {
            maxScore = gestureScore;
            area = "제스처";
        }

        if (emotionScore > maxScore) {
            area = "표정";
        }

        return area;
    }

    private String resolveWeakestArea(
            int postureScore,
            int gazeScore,
            int speechScore,
            int gestureScore,
            int emotionScore
    ) {
        int minScore = postureScore;
        String area = "자세";

        if (gazeScore < minScore) {
            minScore = gazeScore;
            area = "시선";
        }

        if (speechScore < minScore) {
            minScore = speechScore;
            area = "음성";
        }

        if (gestureScore < minScore) {
            minScore = gestureScore;
            area = "제스처";
        }

        if (emotionScore < minScore) {
            area = "표정";
        }

        return area;
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

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nullSafeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Map.of();
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
}