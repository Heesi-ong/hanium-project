package com.hanium.presentation.application.auth;

import com.hanium.presentation.domain.auth.entity.RevokedAccessToken;
import com.hanium.presentation.domain.auth.repository.RevokedAccessTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 로그아웃 API의 다른 트랜잭션 유무와 관계없이 토큰 폐기 행을 즉시 커밋한다.
 *
 * <p>별도 bean으로 분리해야 Spring의 REQUIRES_NEW proxy가 실제로 적용된다.</p>
 */
@Service
public class RevokedAccessTokenWriter {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    public RevokedAccessTokenWriter(RevokedAccessTokenRepository revokedAccessTokenRepository) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String tokenHash, Instant expiresAt) {
        revokedAccessTokenRepository.saveAndFlush(
                RevokedAccessToken.create(tokenHash, expiresAt)
        );
    }
}
