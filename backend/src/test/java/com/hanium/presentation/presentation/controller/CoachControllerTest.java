package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.coach.CoachChatService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.CoachConversationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoachControllerTest {

    private static final String JOB_ID = "20260903120000-abcdef01";
    private static final long OWNER_ID = 9L;

    private CoachChatService coachChatService;
    private CoachController controller;

    @BeforeEach
    void setUp() {
        coachChatService = mock(CoachChatService.class);
        controller = new CoachController(coachChatService);
    }

    private Authentication auth(Object details) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(details);
        return authentication;
    }

    private CoachConversationResponse emptyConversation() {
        return CoachConversationResponse.from(List.of(), 0, 10);
    }

    @Test
    void getMessagesDelegatesWithResolvedUserId() {
        when(coachChatService.getMessages(JOB_ID, OWNER_ID)).thenReturn(emptyConversation());

        ApiResponse<CoachConversationResponse> response =
                controller.getMessages(JOB_ID, auth(OWNER_ID));

        assertThat(response.data()).isNotNull();
        verify(coachChatService).getMessages(JOB_ID, OWNER_ID);
    }

    @Test
    void sendMessagePassesContentThrough() {
        when(coachChatService.sendMessage(JOB_ID, OWNER_ID, "말이 빠른가요?"))
                .thenReturn(emptyConversation());

        controller.sendMessage(
                JOB_ID,
                auth(OWNER_ID),
                new CoachController.CoachSendMessageRequest("말이 빠른가요?")
        );

        verify(coachChatService).sendMessage(JOB_ID, OWNER_ID, "말이 빠른가요?");
    }

    @Test
    void resetConversationDelegates() {
        ApiResponse<Void> response = controller.resetConversation(JOB_ID, auth(OWNER_ID));

        assertThat(response.success()).isTrue();
        verify(coachChatService).resetConversation(JOB_ID, OWNER_ID);
    }

    @Test
    void acceptsNumberAuthenticationDetails() {
        when(coachChatService.getMessages(JOB_ID, 3L)).thenReturn(emptyConversation());

        controller.getMessages(JOB_ID, auth(Integer.valueOf(3)));

        verify(coachChatService).getMessages(JOB_ID, 3L);
    }

    @Test
    void rejectsAuthenticationDetailsWithoutUserId() {
        assertThatThrownBy(() -> controller.getMessages(JOB_ID, auth("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 id");
    }
}
