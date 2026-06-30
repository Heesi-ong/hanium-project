package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient {

    private final OpenAiPromptBuilder openAiPromptBuilder;

    public OpenAiClient(OpenAiPromptBuilder openAiPromptBuilder) {
        this.openAiPromptBuilder = openAiPromptBuilder;
    }

    public OpenAiFeedbackResponse generateFeedback(OpenAiFeedbackRequest request) {
        String systemPrompt = openAiPromptBuilder.buildSystemPrompt();
        String userPrompt = openAiPromptBuilder.buildUserPrompt(request);

        return new OpenAiFeedbackResponse(
                request.jobId(),
                "OpenAI 연결 전 Mock 최종 피드백입니다. 발표자는 전반적으로 안정적인 자세를 보였지만, 시선 처리와 제스처 활용에서 개선 여지가 있습니다.",
                List.of(
                        "상체 자세가 비교적 안정적입니다.",
                        "발표 흐름이 크게 끊기지 않습니다."
                ),
                List.of(
                        "시선이 아래로 향하는 구간을 줄여야 합니다.",
                        "핵심 내용을 말할 때 제스처를 더 적극적으로 사용할 필요가 있습니다."
                ),
                List.of(
                        Map.of(
                                "title", "시선 고정 연습",
                                "description", "핵심 문장을 말할 때 카메라를 2~3초 이상 바라보는 연습을 합니다.",
                                "duration", "5분"
                        ),
                        Map.of(
                                "title", "제스처 강조 연습",
                                "description", "중요 키워드를 말할 때 손동작을 함께 사용하는 연습을 합니다.",
                                "duration", "5분"
                        )
                ),
                List.of(
                        Map.of(
                                "category", "eyeContact",
                                "summary", "일부 구간에서 시선이 아래로 향하는 경향이 있습니다.",
                                "recommendation", "원고 확인 시간을 줄이고 카메라 응시 시간을 늘리는 연습이 필요합니다."
                        ),
                        Map.of(
                                "category", "gesture",
                                "summary", "손동작 사용이 적어 강조 표현이 약하게 보입니다.",
                                "recommendation", "핵심 문장마다 자연스러운 제스처를 함께 사용하는 것이 좋습니다."
                        )
                )
        );
    }
}

// 실제 OpenAI API 연결 시 사용 예정