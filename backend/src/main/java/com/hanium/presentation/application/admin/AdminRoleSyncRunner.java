package com.hanium.presentation.application.admin;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.properties.AdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 {@code admin.emails}(환경변수 {@code ADMIN_EMAILS})와 기존 사용자의 role을
 * 동기화한다. 공개 회원가입/로그인 경로(AuthController)는 더 이상 이메일만으로 ADMIN을
 * 부여하지 않는다 — 관리자 이메일을 아는 누구나 그 주소로 먼저 가입해 관리자 계정을
 * 선점할 수 있었기 때문이다(2026-08-03 실측 재현). 이 러너는 이미 존재하는 사용자의
 * role만 바꾸며 새 사용자를 만들지 않으므로, 실제로 ADMIN이 되려면 (1) 해당 이메일로
 * 먼저 회원가입하고 (2) 운영자가 ADMIN_EMAILS에 그 주소를 넣고 서비스를 재기동해야
 * 한다 — 공개 HTTP 요청만으로는 절대 ADMIN을 만들 수 없다.
 *
 * 회수도 대칭적으로 동작한다: ADMIN_EMAILS에서 이메일을 빼고 재기동하면 해당 사용자는
 * 다음 기동 시 USER로 강등된다.
 */
@Component
public class AdminRoleSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleSyncRunner.class);

    private final UserRepository userRepository;
    private final AdminProperties adminProperties;

    public AdminRoleSyncRunner(UserRepository userRepository, AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.adminProperties = adminProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int promoted = 0;
        for (String email : parseAdminEmails()) {
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null && user.getRole() != UserRole.ADMIN) {
                user.syncRole(UserRole.ADMIN);
                promoted++;
                log.info("ADMIN_ROLE_SYNC_PROMOTED userId={}", user.getId());
            }
        }

        int demoted = 0;
        for (User admin : userRepository.findByRole(UserRole.ADMIN)) {
            if (!adminProperties.isAdminEmail(admin.getEmail())) {
                admin.syncRole(UserRole.USER);
                demoted++;
                log.info("ADMIN_ROLE_SYNC_DEMOTED userId={}", admin.getId());
            }
        }

        if (promoted > 0 || demoted > 0) {
            log.info("ADMIN_ROLE_SYNC_DONE promoted={} demoted={}", promoted, demoted);
        }
    }

    private String[] parseAdminEmails() {
        String emails = adminProperties.emails();
        if (emails == null || emails.isBlank()) {
            return new String[0];
        }

        // userRepository.findByEmail은 정규화된(trim + lowercase) 이메일로 저장된 값과
        // 정확히 일치해야 하므로(AuthController.normalizeEmail과 동일 규칙), 여기서도
        // 같은 규칙으로 맞춘다.
        String[] rawEmails = emails.split("\\s*,\\s*");
        String[] normalizedEmails = new String[rawEmails.length];
        for (int i = 0; i < rawEmails.length; i++) {
            normalizedEmails[i] = rawEmails[i].trim().toLowerCase();
        }

        return normalizedEmails;
    }
}
