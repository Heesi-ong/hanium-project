package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.user.UserOnboardingService;
import com.hanium.presentation.application.user.UserPasswordService;
import com.hanium.presentation.application.user.UserWithdrawalService;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserWithdrawalService userWithdrawalService;
    private final UserOnboardingService userOnboardingService;
    private final UserPasswordService userPasswordService;
    private final UserRateLimiter userRateLimiter;

    public UserController(
            UserWithdrawalService userWithdrawalService,
            UserOnboardingService userOnboardingService,
            UserPasswordService userPasswordService,
            UserRateLimiter userRateLimiter
    ) {
        this.userWithdrawalService = userWithdrawalService;
        this.userOnboardingService = userOnboardingService;
        this.userPasswordService = userPasswordService;
        this.userRateLimiter = userRateLimiter;
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            Authentication authentication,
            @Valid @RequestBody WithdrawRequest request
    ) {
        userWithdrawalService.withdraw(
                getCurrentUserId(authentication),
                request.password()
        );

        return ApiResponse.success("회원탈퇴가 완료되었습니다.");
    }

    @PostMapping("/me/password")
    public ApiResponse<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        Long userId = getCurrentUserId(authentication);

        if (!userRateLimiter.tryConsume("password-change", userId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }

        userPasswordService.changePassword(
                userId,
                request.currentPassword(),
                request.newPassword()
        );

        return ApiResponse.success("비밀번호가 변경되었습니다.");
    }

    @PostMapping("/me/onboarding")
    public ApiResponse<Void> completeOnboarding(
            Authentication authentication,
            @Valid @RequestBody OnboardingRequest request
    ) {
        userOnboardingService.completeOnboarding(
                getCurrentUserId(authentication),
                request.purpose(),
                request.experienceLevel(),
                request.improvementGoal()
        );

        return ApiResponse.success("온보딩 정보가 저장되었습니다.");
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

    public record WithdrawRequest(
            @NotBlank(message = "비밀번호는 필수입니다.")
            String password
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "현재 비밀번호는 필수입니다.")
            String currentPassword,

            @NotBlank(message = "새 비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해야 합니다.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                    message = "비밀번호는 영문자와 숫자를 각각 1자 이상 포함해야 합니다."
            )
            String newPassword
    ) {
    }

    public record OnboardingRequest(
            @NotBlank(message = "목적은 필수입니다.")
            @Pattern(
                    regexp = "^(INTERVIEW|PRESENTATION|LECTURE|OTHER)$",
                    message = "목적 값이 올바르지 않습니다."
            )
            String purpose,

            @NotBlank(message = "경험 수준은 필수입니다.")
            @Pattern(
                    regexp = "^(BEGINNER|INTERMEDIATE|ADVANCED)$",
                    message = "경험 수준 값이 올바르지 않습니다."
            )
            String experienceLevel,

            @NotBlank(message = "개선 목표는 필수입니다.")
            @Pattern(
                    regexp = "^(VOICE_TONE|PACE|EYE_CONTACT|POSTURE|CONTENT_STRUCTURE|OTHER)$",
                    message = "개선 목표 값이 올바르지 않습니다."
            )
            String improvementGoal
    ) {
    }
}
