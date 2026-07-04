package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.user.UserWithdrawalService;
import com.hanium.presentation.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserWithdrawalService userWithdrawalService;

    public UserController(UserWithdrawalService userWithdrawalService) {
        this.userWithdrawalService = userWithdrawalService;
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
}
