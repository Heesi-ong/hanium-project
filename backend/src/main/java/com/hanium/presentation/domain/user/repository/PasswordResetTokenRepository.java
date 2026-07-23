package com.hanium.presentation.domain.user.repository;

import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from PasswordResetToken token where token.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 사용자당 활성(미사용) 재설정 토큰은 항상 1개만 유효하도록, 새 토큰 발급 직전과 비밀번호
    // 변경 성공 직후 그 사용자의 나머지 미사용 토큰을 모두 무효화(usedAt 기록)합니다.
    // isUsable()이 usedAt == null을 요구하므로, 이 시점부터 해당 토큰들은 즉시 사용 불가능해집니다.
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.user = :user AND t.usedAt IS NULL")
    int invalidateActiveTokensForUser(@Param("user") User user, @Param("now") LocalDateTime now);

    // 만료된 지 오래된 토큰(사용/무효화 여부 무관)을 주기적으로 정리하기 위한 삭제 쿼리입니다.
    // expiresAt은 토큰이 무효화되어도 바뀌지 않으므로, 사용/무효화된 토큰도 원래 만료 시각을
    // 기준으로 자연스럽게 정리 대상에 포함됩니다.
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
