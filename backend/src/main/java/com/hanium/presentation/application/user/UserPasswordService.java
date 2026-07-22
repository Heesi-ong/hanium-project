package com.hanium.presentation.application.user;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPasswordService {

    private static final Logger log = LoggerFactory.getLogger(UserPasswordService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserPasswordService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        // 새 비밀번호가 현재 비밀번호와 같으면 changePasswordHash()가 passwordChangedAt만
        // 갱신하고 실질적인 변경은 없는 상태가 되므로, 사용자에게 명확히 알려줍니다.
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "새 비밀번호는 현재 비밀번호와 달라야 합니다."
            );
        }

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        log.info("USER_PASSWORD_CHANGED userId={} 비밀번호가 변경되었습니다.", userId);
    }
}
