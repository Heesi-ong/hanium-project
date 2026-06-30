package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import org.springframework.stereotype.Component;

@Component
public class OpenAiPromptBuilder {

    public String buildSystemPrompt() {
        return """
                당신은 발표 코칭 전문가입니다.
                사용자의 발표 분석 데이터를 바탕으로 구체적이고 실천 가능한 피드백을 생성하세요.

                피드백 기준:
                1. 점수 기반 분석을 우선 반영합니다.
                2. 시선, 자세, 제스처, 표정, 음성 속도, 침묵, 필러 표현을 종합합니다.
                3. 과도하게 단정하지 말고 관찰 데이터 기반으로 설명합니다.
                4. 사용자가 바로 연습할 수 있는 개선 방법을 제안합니다.
                5. 결과는 구조화된 JSON 형태로 생성되어야 합니다.
                """;
    }

    public String buildUserPrompt(OpenAiFeedbackRequest request) {
        return """
                다음은 발표 분석 결과입니다.

                jobId:
                %s

                compactAnalysis:
                %s

                위 데이터를 바탕으로 다음 항목을 생성하세요.

                - overallFeedback
                - strengths
                - improvements
                - practicePlan
                - timelineFeedback
                """.formatted(
                request.jobId(),
                request.compactAnalysis()
        );
    }
}