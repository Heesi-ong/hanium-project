package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.coach.CoachChatService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.CoachConversationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/results/{jobId}/coach")
public class CoachController {

    // ResultController와 동일한 jobId 형식 검증 (경로 조작 방지 목적)
    private static final String JOB_ID_PATTERN = "^\\d{14}-[0-9a-f]{8}$";
    private static final String JOB_ID_MESSAGE = "jobId 형식이 올바르지 않습니다.";

    private final CoachChatService coachChatService;

    public CoachController(CoachChatService coachChatService) {
        this.coachChatService = coachChatService;
    }

    @GetMapping("/messages")
    public ApiResponse<CoachConversationResponse> getMessages(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        CoachConversationResponse response = coachChatService.getMessages(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success("대화 이력 조회가 완료되었습니다.", response);
    }

    @PostMapping("/messages")
    public ApiResponse<CoachConversationResponse> sendMessage(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication,
            @Valid @RequestBody CoachSendMessageRequest request
    ) {
        CoachConversationResponse response = coachChatService.sendMessage(
                jobId,
                getCurrentUserId(authentication),
                request.content()
        );

        return ApiResponse.success("메시지가 전송되었습니다.", response);
    }

    @DeleteMapping("/messages")
    public ApiResponse<Void> resetConversation(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        coachChatService.resetConversation(jobId, getCurrentUserId(authentication));

        return ApiResponse.success("대화가 초기화되었습니다.");
    }

    private Long getCurrentUserId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("인증 정보에서 사용자 id를 찾을 수 없습니다.");
    }

    public record CoachSendMessageRequest(
            @NotBlank(message = "메시지 내용은 필수입니다.")
            @Size(max = 1000, message = "메시지는 1000자를 초과할 수 없습니다.")
            String content
    ) {
    }
}
