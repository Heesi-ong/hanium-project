package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.properties.AnalysisEngineProperties;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class AnalysisEngineClientReadinessTest {

    @Test
    void checkReadinessPreservesModelReadinessDetailsFromEngine() {
        Fixture fixture = createFixture("correct-key");

        fixture.server.expect(requestTo("http://localhost:8001/api/internal/readiness"))
                .andExpect(header("X-Internal-Api-Key", "correct-key"))
                .andRespond(withSuccess(
                        """
                                {
                                  "service":"analysis-engine",
                                  "ready":false,
                                  "models":{"whisper":true,"pose":false,"face":true},
                                  "reason":"Analysis models are not loaded: pose"
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(true);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "analysis")
                .tag("outcome", "not_ready")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(result.get("response"))
                .isInstanceOfSatisfying(Map.class, response -> {
                    assertThat(response.get("reason").toString()).contains("pose");
                    assertThat(response.get("models"))
                            .isInstanceOfSatisfying(Map.class, models -> {
                                assertThat(models.get("whisper")).isEqualTo(true);
                                assertThat(models.get("pose")).isEqualTo(false);
                                assertThat(models.get("face")).isEqualTo(true);
                            });
                });

        fixture.server.verify();
    }

    @Test
    void checkReadinessReportsReachableButNotAuthenticatedWhenKeyIsWrong() {
        Fixture fixture = createFixture("wrong-key");

        fixture.server.expect(requestTo("http://localhost:8001/api/internal/readiness"))
                .andRespond(withUnauthorizedRequest());

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(false);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "analysis")
                .tag("outcome", "unauthenticated")
                .counter()
                .count()).isEqualTo(1.0);

        fixture.server.verify();
    }

    @Test
    void checkReadinessReportsUnreachableWhenEngineDoesNotRespond() {
        Fixture fixture = createFixture("correct-key");

        fixture.server.expect(requestTo("http://localhost:8001/api/internal/readiness"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(false);
        assertThat(result.get("authenticated")).isEqualTo(false);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "analysis")
                .tag("outcome", "unreachable")
                .counter()
                .count()).isEqualTo(1.0);

        fixture.server.verify();
    }

    private Fixture createFixture(String apiKey) {
        AnalysisEngineProperties properties = new AnalysisEngineProperties("http://localhost:8001", apiKey);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisEngineClient client = new AnalysisEngineClient(builder, properties, registry);

        return new Fixture(client, server, registry);
    }

    private record Fixture(
            AnalysisEngineClient client,
            MockRestServiceServer server,
            SimpleMeterRegistry registry
    ) {
    }
}
