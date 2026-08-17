package com.hanium.presentation.infrastructure.client.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.infrastructure.client.openai.dto.CoachingProfile;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiPromptBuilderPersonalizationTest {

    @Test
    void userPromptIncludesCoachingProfileAsAdviceContext() {
        OpenAiPromptBuilder builder = new OpenAiPromptBuilder(new ObjectMapper());

        String prompt = builder.buildUserPrompt(new OpenAiFeedbackRequest(
                "job-1",
                Map.of("totalScore", 80),
                CoachingProfile.of("PRESENTATION", "ADVANCED", "POSTURE")
        ));

        assertThat(prompt)
                .contains("coachingProfile")
                .contains("PRESENTATION")
                .contains("ADVANCED")
                .contains("POSTURE")
                .doesNotContain("email");
    }
}
