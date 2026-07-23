package com.hanium.presentation.global.config;

import com.hanium.presentation.application.auth.PasswordResetOutboxCrypto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * prod에서 SMTP_HOST가 비어 있으면 비밀번호 재설정 이메일이 조용히 발송되지 않습니다
 * (PasswordResetEmailSender는 prod에서 링크를 로그에도 남기지 않음). 이 상태를 경고
 * 로그만 남기고 넘어가면, 운영자가 서비스는 정상 기동됐다고 착각한 채 모든 사용자의
 * 계정 복구가 실제로는 불가능한 상태로 배포될 수 있다(2026-07-23 코드 리뷰 P1-02).
 *
 * 정책을 다음과 같이 고정한다:
 *  - password-reset.enabled=true(기본값)인데 SMTP_HOST가 비어 있으면 운영자가 이
 *    상태를 "깜빡한 것"으로 보고 기동 자체를 막는다(fail-fast). PasswordResetService도
 *    같은 플래그를 보고 요청/확인 API를 명시적으로 거절해 이중으로 방어한다.
 *  - password-reset.enabled=false면 운영자가 이 기능을 의도적으로 끈 것이므로
 *    SMTP 여부와 무관하게 기동을 막지 않는다.
 */
@Component
@Profile("prod")
public class PasswordResetMailStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailStartupValidator.class);

    private final String smtpHost;
    private final boolean passwordResetEnabled;
    private final String outboxEncryptionKey;

    public PasswordResetMailStartupValidator(
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${password-reset.enabled:true}") boolean passwordResetEnabled,
            @Value("${password-reset.outbox.encryption-key:" + PasswordResetOutboxCrypto.LOCAL_DEFAULT_SECRET + "}")
            String outboxEncryptionKey
    ) {
        this.smtpHost = smtpHost;
        this.passwordResetEnabled = passwordResetEnabled;
        this.outboxEncryptionKey = outboxEncryptionKey;
    }

    @PostConstruct
    public void validate() {
        if (!passwordResetEnabled) {
            log.info(
                    "PASSWORD_RESET_DISABLED_BY_CONFIG password-reset.enabled=false로 비밀번호 재설정 "
                            + "기능이 의도적으로 비활성화되어 있습니다. SMTP 설정 여부와 무관하게 요청/확인 API는 거절됩니다."
            );
            return;
        }

        if (smtpHost == null || smtpHost.isBlank()) {
            throw new IllegalStateException(
                    "PASSWORD_RESET_MAIL_NOT_CONFIGURED prod 프로필에서 SMTP_HOST가 비어 있어 비밀번호 재설정 "
                            + "이메일을 보낼 수 없습니다. SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD를 설정하거나, "
                            + "이 기능을 의도적으로 쓰지 않는다면 PASSWORD_RESET_ENABLED=false로 명시하세요."
            );
        }

        if (outboxEncryptionKey == null
                || outboxEncryptionKey.length() < 32
                || PasswordResetOutboxCrypto.LOCAL_DEFAULT_SECRET.equals(outboxEncryptionKey)) {
            throw new IllegalStateException(
                    "PASSWORD_RESET_OUTBOX_ENCRYPTION_KEY는 prod에서 로컬 기본값이 아닌 "
                            + "32자 이상의 별도 비밀값이어야 합니다."
            );
        }
    }
}
