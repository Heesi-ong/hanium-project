package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpenAiPromptBuilder {

    private final ObjectMapper objectMapper;

    public OpenAiPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String buildSystemPrompt() {
        return """
                당신은 발표 영상을 분석하는 AI 발표 코칭 전문가입니다.

                역할:
                - 사용자의 발표 분석 데이터를 바탕으로 구체적이고 실행 가능한 피드백을 생성합니다.
                - 점수, 검출률, 비율, STT 결과 등 제공된 데이터만 근거로 사용합니다.
                - 제공되지 않은 사실을 임의로 만들지 않습니다.
                - MediaPipe, STT, Video LLM 결과는 추정 기반 분석이므로 확정적 진단처럼 표현하지 않습니다.
                - 관찰된 수치와 개선 조언을 구분해서 작성합니다.
                - 사용자가 바로 연습할 수 있는 형태로 조언합니다.

                반드시 지켜야 할 출력 규칙:
                - 한국어로 작성합니다.
                - JSON만 반환합니다.
                - 마크다운을 사용하지 않습니다.
                - 코드블록을 사용하지 않습니다.
                - 불필요한 설명 문장을 JSON 밖에 붙이지 않습니다.
                - schema에 정의된 필드만 반환합니다.

                출력 JSON 필드:
                - overall: 발표 전체 종합 피드백
                - strengths: 강점 목록
                - improvements: 개선점 목록
                - practicePlan: 연습 계획 목록
                - timelineFeedback: 영역별 타임라인 피드백 목록

                피드백 작성 기준:
                - totalScore는 전체 수준 판단에 사용합니다.
                - postureScore는 자세 안정성 판단에 사용합니다.
                - gazeScore는 시선 처리 판단에 사용합니다.
                - speechScore는 말하기 속도, 침묵, 음성 흐름 판단에 사용합니다.
                - gestureScore는 제스처 사용 판단에 사용합니다.
                - expressionScore는 표정 변화와 발표 몰입감 판단에 사용합니다. (감정 상태 분류는 참고용 보조 지표이며 점수에는 포함되지 않습니다)
                - STT transcript는 발표 내용 참고용으로만 사용하고, 별도 내용 분석이 없는 경우 논리 구조를 단정하지 않습니다.
                """;
    }

    public String buildUserPrompt(OpenAiFeedbackRequest request) {
        Map<String, Object> promptPayload = new LinkedHashMap<>();

        promptPayload.put("jobId", request.jobId());
        promptPayload.put("requestPurpose", "발표 분석 결과를 바탕으로 최종 피드백을 생성합니다.");
        promptPayload.put("outputLanguage", "ko-KR");
        promptPayload.put("compactAnalysis", request.compactAnalysis());
        promptPayload.put("generationGuidelines", createGenerationGuidelines());

        return toPrettyJson(promptPayload);
    }

    private Map<String, Object> createGenerationGuidelines() {
        Map<String, Object> guidelines = new LinkedHashMap<>();

        guidelines.put("overall", "총점과 가장 강한 영역, 가장 약한 영역을 포함해 3~5문장으로 작성합니다.");
        guidelines.put("strengths", "점수가 높거나 안정적으로 분석된 항목을 2~5개 작성합니다.");
        guidelines.put("improvements", "점수가 낮거나 개선이 필요한 항목을 2~5개 작성합니다.");
        guidelines.put("practicePlan", "사용자가 바로 따라 할 수 있는 연습 계획을 2~5개 작성합니다.");
        guidelines.put("timelineFeedback", "speech, posture, gaze, gesture, emotion 영역을 중심으로 작성합니다.");
        guidelines.put("doNotInvent", "데이터에 없는 발표 주제, 사용자 신상, 발표 목적, 청중 반응을 임의로 만들지 않습니다.");
        guidelines.put("avoidMedicalOrPsychologicalClaim", "표정이나 자세를 심리 상태나 건강 상태로 단정하지 않습니다.");
        guidelines.put("tone", "비판적이기보다 코칭형 문체로 작성합니다.");

        return guidelines;
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenAI 프롬프트 JSON 변환에 실패했습니다.", exception);
        }
    }
}