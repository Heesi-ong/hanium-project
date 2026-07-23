package com.hanium.presentation.global.config;

/**
 * Redis 캐시가 아니라 DB 최종 원장의 읽기/쓰기도 실패해 토큰 폐기 상태를 안전하게
 * 판정하거나 기록할 수 없을 때 발생한다. 호출자는 인증을 허용하면 안 된다.
 */
public class JwtRevocationUnavailableException extends RuntimeException {

    public JwtRevocationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
