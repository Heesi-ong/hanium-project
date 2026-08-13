package com.hanium.presentation.infrastructure;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RealInfrastructureContainerTest {

    private static final String REDIS_PASSWORD = "testcontainers-redis-password";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hanium_test")
            .withUsername("hanium")
            .withPassword("hanium-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    )
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Test
    void flywayMigrationsApplyToRealMySql84() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        var migrateResult = flyway.migrate();

        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrationsExecuted).isPositive();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("SELECT VERSION()")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).startsWith("8.4");
            }
            try (ResultSet history = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"
            )) {
                assertThat(history.next()).isTrue();
                assertThat(history.getInt(1)).isEqualTo(migrateResult.migrationsExecuted);
            }
        }
    }

    @Test
    void redisAuthenticationAndTtlWorkAgainstRealRedis7() {
        RedisURI redisUri = RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(6379))
                .withPassword(REDIS_PASSWORD.toCharArray())
                .withTimeout(Duration.ofSeconds(2))
                .build();
        RedisClient client = RedisClient.create(redisUri);

        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            var commands = connection.sync();
            assertThat(commands.ping()).isEqualTo("PONG");
            assertThat(commands.setex("hanium:test:ttl", 30, "active")).isEqualTo("OK");
            assertThat(commands.get("hanium:test:ttl")).isEqualTo("active");
            assertThat(commands.ttl("hanium:test:ttl")).isBetween(1L, 30L);
        } finally {
            client.shutdown();
        }
    }
}
