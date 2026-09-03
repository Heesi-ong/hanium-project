package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// increment와 TTL 설정이 하나의 원자적 Redis 왕복(Lua 스크립트)으로 이뤄지는지 실제 Redis를
// 대상으로 검증한다. 예전에는 두 번의 별도 호출이라, INCR은 성공하고 EXPIRE만 실패하면
// 카운터가 TTL 없이 영구히 남아 해당 사용자/버킷을 영구 차단할 수 있었다.
//
// 예전에는 외부 Redis가 있어야만 검증됐고 CI(backend job)엔 Redis가 없어 항상 skip됐다.
// backend job에 공용 Redis 서비스를 추가하는 방법은, IP rate limit 상태가 통합 테스트
// 컨텍스트 간에 공유되면서 다른 로그인 통합 테스트를 429로 오염시켜(2026-09-03 실측) 불가.
// 대신 이 테스트 전용 Testcontainers Redis를 띄우고 @DynamicPropertySource로만 연결해,
// 다른 테스트에 영향 없이 메인 test 실행(=커버리지 게이트 대상)에 포함되게 한다.
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class UserRateLimiterIntegrationTest {

    private static final String REDIS_PASSWORD = "userratelimiter-it-redis-password";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    )
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void firstConsumeAtomicallySetsCountAndTtlInOneRoundTrip() {
        String testKey = "atomic-test:" + System.nanoTime();
        String redisKey = "rate-limit:login:" + testKey;
        redisTemplate.delete(redisKey);

        boolean allowed = userRateLimiter.tryConsume("login", testKey);

        assertThat(allowed).isTrue();
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("1");

        Long ttlSeconds = redisTemplate.getExpire(redisKey);
        // TTL이 -1(만료 없음)이거나 -2(키 없음)면 안 된다 - 정확히 이 상태가 원래 버그였다.
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isGreaterThan(0L);
        assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());

        redisTemplate.delete(redisKey);
    }

    @Test
    void subsequentConsumesIncrementWithoutResettingTtl() {
        String testKey = "atomic-test-repeat:" + System.nanoTime();
        String redisKey = "rate-limit:login:" + testKey;
        redisTemplate.delete(redisKey);

        userRateLimiter.tryConsume("login", testKey);
        Long firstTtl = redisTemplate.getExpire(redisKey);

        userRateLimiter.tryConsume("login", testKey);
        userRateLimiter.tryConsume("login", testKey);

        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("3");

        Long laterTtl = redisTemplate.getExpire(redisKey);
        assertThat(laterTtl).isNotNull();
        // TTL이 새로 세팅되지 않고 계속 줄어들기만 해야 한다(처음 것보다 크면 안 됨).
        assertThat(laterTtl).isLessThanOrEqualTo(firstTtl);

        redisTemplate.delete(redisKey);
    }

    @Test
    void weightedReservationIsAtomicAndDoesNotExceedCapacity() {
        String testKey = "atomic-weighted:" + System.nanoTime();
        String redisKey = "rate-limit:login-ip:" + testKey;
        redisTemplate.delete(redisKey);

        assertThat(userRateLimiter.tryConsume("login-ip", testKey, 7)).isTrue();
        assertThat(userRateLimiter.tryConsume("login-ip", testKey, 13)).isTrue();
        assertThat(userRateLimiter.tryConsume("login-ip", testKey, 1)).isFalse();

        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("20");
        assertThat(redisTemplate.getExpire(redisKey)).isPositive();

        redisTemplate.delete(redisKey);
    }
}
