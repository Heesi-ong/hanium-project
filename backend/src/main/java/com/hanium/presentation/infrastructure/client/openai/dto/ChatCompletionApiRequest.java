package com.hanium.presentation.infrastructure.client.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// OpenAI의 옛 Chat Completions API(POST /chat/completions) 요청 형식입니다. 코치 채팅은
// 구조화 JSON 스키마 출력이 필요 없는 자유 텍스트 응답이라, OpenAI와 NVIDIA NIM(build.nvidia.com)
// 양쪽 모두가 지원하는 이 표준 형식을 그대로 씁니다 - 나중에 실제 OpenAI 키로 바꿀 때
// 코드 변경 없이 provider 설정값만 바꾸면 됩니다.
//
// response_format은 코치 채팅(자유 텍스트)에서는 null로 두어 요청 JSON에서 아예 생략되고,
// 분석 피드백(OpenAiClient)에서만 json_schema를 실어 보냅니다 - NVIDIA NIM의 vLLM
// 백엔드가 response_format=json_object는 스키마 없이 거부하지만(400), json_schema는
// grammar-constrained decoding으로 지원한다는 걸 실측으로 확인했습니다(2026-08-13).
public record ChatCompletionApiRequest(
        String model,
        List<Message> messages,
        double temperature,
        int max_tokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object response_format
) {
    public ChatCompletionApiRequest(String model, List<Message> messages, double temperature, int max_tokens) {
        this(model, messages, temperature, max_tokens, null);
    }

    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
