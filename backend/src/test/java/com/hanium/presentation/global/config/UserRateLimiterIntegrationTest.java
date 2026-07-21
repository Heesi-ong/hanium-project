package com.hanium.presentation.global.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// increment와 TTL 설정이 하나의 원자적 Redis 왕복(Lua 스크립트)으로 이뤄지는지 실제 Redis를
// 대상으로 검증한다. 예전에는 두 번의 별도 호출이라, INCR은 성공하고 EXPIRE만 실패하면
// 카운터가 TTL 없이 영구히 남아 해당 사용자/버킷을 영구 차단할 수 있었다.
//
// 이 프로젝트의 UserRateLimiter는 Redis에 연결할 수 없으면 조용히 로컬 폴백으로 넘어가도록
// 설계돼 있어(운영에서 원하는 동작), Redis가 없는 환경(예: 이 CI 잡은 별도 Redis 서비스
// 컨테이너 없이 `./gradlew test`만 실행함, REDIS_PASSWORD를 export하지 않은 로컬 셸 등)
// 에서는 이 테스트가 검증하려는 "실제 Redis 상태"를 관찰할 수 없다. 그런 환경에서는
// 실패 대신 건너뛴다(ffprobe/ffmpeg 가용성에 따라 건너뛰는 다른 테스트와 같은 패턴).
@SpringBootTest
class UserRateLimiterIntegrationTest {

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void assumeRealRedisIsReachable() {
        try {
            redisTemplate.opsForValue().get("rate-limit:connectivity-check");
        } catch (RuntimeException exception) {
            assumeTrue(false, "real Redis not reachable/authenticated in this environment: " + exception);
        }
    }

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
}
