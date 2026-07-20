package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// AuthControllerIntegrationTest 등 기존 통합 테스트는 이 클래스를 @MockBean으로
// 완전히 대체하기 때문에, 실제 분기(SMTP 설정됨/dev 폴백/prod 무음 실패)는 어디서도
// 검증되지 않고 있었다. 비밀번호 재설정 이메일은 보안 관련 기능이라 별도로 검증한다.
class PasswordResetEmailSenderTest {

    private static final String RESET_LINK = "https://example.com/reset-password?token=abc123";

    @Test
    void sendsRealEmailWhenSmtpHostIsConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"local"});

        PasswordResetEmailSender sender = new PasswordResetEmailSender(
                mailSender, environment, "smtp.example.com", "no-reply@example.com"
        );
        User user = User.create("reset-target@example.com", "hashed-password");

        sender.sendPasswordResetLink(user, RESET_LINK);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage sentMessage = messageCaptor.getValue();
        assertThat(sentMessage.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(sentMessage.getTo()).containsExactly("reset-target@example.com");
        assertThat(sentMessage.getSubject()).contains("비밀번호 재설정");
        assertThat(sentMessage.getText()).contains(RESET_LINK);
    }

    @Test
    void fallsBackToLogWithoutSendingWhenSmtpHostIsBlankInNonProdProfile() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        PasswordResetEmailSender sender = new PasswordResetEmailSender(
                mailSender, environment, "", "no-reply@example.com"
        );
        User user = User.create("dev-user@example.com", "hashed-password");

        sender.sendPasswordResetLink(user, RESET_LINK);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void silentlySkipsSendingWhenSmtpHostIsBlankInProdProfile() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

        PasswordResetEmailSender sender = new PasswordResetEmailSender(
                mailSender, environment, null, "no-reply@example.com"
        );
        User user = User.create("prod-user@example.com", "hashed-password");

        sender.sendPasswordResetLink(user, RESET_LINK);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
