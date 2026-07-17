package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * prod에서 SMTP_HOST가 비어 있으면 비밀번호 재설정 이메일이 조용히 발송되지 않습니다
 * (PasswordResetEmailSender는 prod에서 링크를 로그에도 남기지 않음). 운영자가 이 설정을
 * 깜빡한 채 배포하는 것을 기동 시점에 눈에 띄게 알리기 위한 경고입니다.
 *
 * JWT 시크릿과 달리 이메일 미설정은 서비스 기동을 막을 만큼 치명적이지는 않으므로
 * fail-fast(예외)로 막지 않고 경고 로그만 남깁니다. 실제 강제하려면 정책 재검토 필요.
 */
@Component
@Profile("prod")
public class PasswordResetMailStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailStartupValidator.class);

    private final String smtpHost;

    public PasswordResetMailStartupValidator(
            @Value("${spring.mail.host:}") String smtpHost
    ) {
        this.smtpHost = smtpHost;
    }

    @PostConstruct
    public void validate() {
        if (smtpHost == null || smtpHost.isBlank()) {
            log.warn(
                    "PASSWORD_RESET_MAIL_NOT_CONFIGURED prod 프로필인데 SMTP_HOST가 비어 있습니다. "
                            + "비밀번호 재설정 이메일이 실제로 발송되지 않습니다. "
                            + "SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD를 설정하세요."
            );
        }
    }
}
