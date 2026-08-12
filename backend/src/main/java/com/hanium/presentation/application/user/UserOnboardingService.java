package com.hanium.presentation.application.user;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserOnboardingService {

    private final UserRepository userRepository;

    public UserOnboardingService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void completeOnboarding(
            Long userId,
            String purpose,
            String experienceLevel,
            String improvementGoal
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        user.completeOnboarding(purpose, experienceLevel, improvementGoal);
    }

    @Transactional
    public void skipOnboarding(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        user.skipOnboarding();
    }
}
