package com.hanium.presentation.infrastructure.client;

import com.hanium.presentation.global.properties.EngineProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AbstractEngineClient}의 공통 health/readiness/baseUrl 동작을 최소 테스트용 하위
 * 클래스로 직접 검증합니다. 하위 구현체(AnalysisEngineClient/VideoLlmEngineClient)별 테스트는
 * {@code analyze()}와 서킷브레이커에 집중하고 있어, 이 공통 계층은 별도로 특징화합니다.
 */
class AbstractEngineClientTest {

    private record TestEngineProperties(String baseUrl, String apiKey) implements EngineProperties {
    }

    private static final class TestEngineClient extends AbstractEngineClient {
        private TestEngineClient(
                RestClient.Builder builder,
                EngineProperties properties,
                SimpleMeterRegistry registry
        ) {
            super(builder, properties, registry, "test", "테스트 엔진");
        }
    }

    private record Fixture(
            TestEngineClient client,
            MockRestServiceServer server,
            SimpleMeterRegistry registry
    ) {
    }

    private Fixture fixture(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestEngineClient client = new TestEngineClient(
                builder,
                new TestEngineProperties("http://engine:9999", apiKey),
                registry
        );
        return new Fixture(client, server, registry);
    }

    @Test
    void checkHealthReportsUpWithEngineResponseBodyWhenReachable() {
        Fixture fixture = fixture("k");
        fixture.server.expect(requestTo("http://engine:9999/health"))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\",\"detail\":\"warm\"}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> result = fixture.client.checkHealth();

        assertThat(result.get("status")).isEqualTo("up");
        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("response"))
                .isInstanceOfSatisfying(Map.class, body -> assertThat(body.get("detail")).isEqualTo("warm"));
        fixture.server.verify();
    }

    @Test
    void checkHealthReportsDownWhenEngineIsUnreachable() {
        Fixture fixture = fixture("k");
        fixture.server.expect(requestTo("http://engine:9999/health"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        Map<String, Object> result = fixture.client.checkHealth();

        assertThat(result.get("status")).isEqualTo("down");
        assertThat(result.get("reachable")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("ResourceAccessException");
        assertThat(result.get("message").toString()).isNotBlank();
        fixture.server.verify();
    }

    @Test
    void checkReadinessReportsReadyAndRecordsReadyOutcomeMetric() {
        Fixture fixture = fixture("secret");
        fixture.server.expect(requestTo("http://engine:9999/api/internal/readiness"))
                .andExpect(header("X-Internal-Api-Key", "secret"))
                .andRespond(withSuccess(
                        "{\"service\":\"test\",\"ready\":true}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(true);
        assertThat(result.get("ready")).isEqualTo(true);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "test")
                .tag("outcome", "ready")
                .counter()
                .count()).isEqualTo(1.0);
        fixture.server.verify();
    }

    @Test
    void checkReadinessTreats5xxAsUnreachable() {
        Fixture fixture = fixture("secret");
        fixture.server.expect(requestTo("http://engine:9999/api/internal/readiness"))
                .andRespond(withServerError());

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(false);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "test")
                .tag("outcome", "unreachable")
                .counter()
                .count()).isEqualTo(1.0);
        fixture.server.verify();
    }

    @Test
    void checkReadinessSendsEmptyHeaderWhenApiKeyIsNull() {
        Fixture fixture = fixture(null);
        fixture.server.expect(requestTo("http://engine:9999/api/internal/readiness"))
                .andExpect(header("X-Internal-Api-Key", ""))
                .andRespond(withSuccess("{\"ready\":false}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("ready")).isEqualTo(false);
        fixture.server.verify();
    }

    @Test
    void getBaseUrlExposesConfiguredEngineBaseUrl() {
        assertThat(fixture("k").client.getBaseUrl()).isEqualTo("http://engine:9999");
    }
}
