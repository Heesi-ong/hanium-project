package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.infrastructure.client.openai.dto.ChatCompletionApiRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CoachPromptBuilder {

    private final ObjectMapper objectMapper;

    public CoachPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ChatCompletionApiRequest.Message> buildMessages(
            Map<String, Object> compactAnalysis,
            List<Map<String, Object>> historySummary,
            List<OpenAiCoachChatRequest.ChatTurn> history,
            String newUserMessage
    ) {
        List<ChatCompletionApiRequest.Message> messages = new ArrayList<>();
        messages.add(ChatCompletionApiRequest.Message.system(
                buildSystemPrompt(compactAnalysis, historySummary)
        ));

        for (OpenAiCoachChatRequest.ChatTurn turn : history) {
            if ("ASSISTANT".equals(turn.role())) {
                messages.add(ChatCompletionApiRequest.Message.assistant(turn.content()));
            } else {
                messages.add(ChatCompletionApiRequest.Message.user(turn.content()));
            }
        }

        messages.add(ChatCompletionApiRequest.Message.user(newUserMessage));
        return messages;
    }

    private String buildSystemPrompt(
            Map<String, Object> compactAnalysis,
            List<Map<String, Object>> historySummary
    ) {
        boolean hasHistory = historySummary != null && !historySummary.isEmpty();

        String basePrompt = """
                당신은 발표 영상을 분석하는 AI 발표 코칭 전문가입니다.
                사용자는 이미 완료된 발표 분석 결과를 보면서, 그 결과에 대해 후속 질문을 하거나
                더 구체적인 연습 방법을 물어봅니다.

                역할:
                - 아래 제공된 분석 데이터(JSON)만 근거로 답변합니다. 제공되지 않은 사실을 임의로 만들지 않습니다.
                - MediaPipe, STT, Video LLM 결과는 추정 기반 분석이므로 확정적 진단처럼 표현하지 않습니다.
                - 표정이나 자세를 심리 상태나 건강 상태로 단정하지 않습니다.
                - 비판적이기보다 코칭형 문체로, 사용자가 바로 연습할 수 있는 조언을 제공합니다.
                - 분석 데이터와 무관한 질문에는 정중히 발표 코칭 주제로 안내합니다.
                """
                + (hasHistory
                        ? """
                        - "과거 발표 점수 이력"은 사용자가 진행 상황이나 변화를 물어볼 때만 참고합니다.
                          현재 발표에 대한 질문에는 이력을 끌어오지 않고 현재 분석 데이터로만 답합니다.
                        """
                        : "")
                + """

                출력 규칙:
                - 한국어 평문으로 답변합니다.
                - JSON, 마크다운, 코드블록을 사용하지 않습니다.
                - 2~5문장 내외로 간결하게 답변합니다.

                분석 데이터(JSON):
                """ + toJson(compactAnalysis);

        if (!hasHistory) {
            return basePrompt;
        }

        return basePrompt + """


                과거 발표 점수 이력(JSON, 최근 순, 참고용):
                """ + toJson(historySummary);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
