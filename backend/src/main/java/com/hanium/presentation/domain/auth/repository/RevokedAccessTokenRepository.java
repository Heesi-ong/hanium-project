package com.hanium.presentation.domain.auth.repository;

import com.hanium.presentation.domain.auth.entity.RevokedAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    List<RevokedAccessToken> findTop500ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff);
}
