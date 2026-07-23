package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class PasswordResetEmailSender {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailSender.class);

    private final JavaMailSender mailSender;
    private final Environment environment;
    private final String smtpHost;
    private final String mailFromAddress;

    public PasswordResetEmailSender(
            JavaMailSender mailSender,
            Environment environment,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${password-reset.mail-from-address:no-reply@example.com}") String mailFromAddress
    ) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.smtpHost = smtpHost;
        this.mailFromAddress = mailFromAddress;
    }

    public void sendPasswordResetLink(User user, String resetLink) {
        sendPasswordResetLink(user.getEmail(), user.getId(), resetLink);
    }

    public void sendPasswordResetLink(String recipientEmail, Long userId, String resetLink) {
        if (smtpHost == null || smtpHost.isBlank()) {
            if (isProdProfile()) {
                throw new IllegalStateException(
                        "PASSWORD_RESET_EMAIL_NOT_SENT smtpHost is empty in prod profile. userId=" + userId
                );
            }

            log.warn(
                    "PASSWORD_RESET_DEV_FALLBACK 개발용 폴백: SMTP_HOST가 없어 비밀번호 재설정 링크를 로그로 출력합니다. userId={} resetLink={}",
                    userId,
                    resetLink
            );
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFromAddress);
        message.setTo(recipientEmail);
        message.setSubject("AI Presentation Coach 비밀번호 재설정");
        message.setText("""
                비밀번호 재설정을 요청하셨습니다.

                아래 링크가 만료되기 전에 새 비밀번호를 설정하세요.
                %s

                본인이 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(resetLink));

        mailSender.send(message);
    }

    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
