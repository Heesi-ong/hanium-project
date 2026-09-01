package com.hanium.presentation.infrastructure.client.videollm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
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

class VideoLlmEngineClientReadinessTest {

    @Test
    void checkReadinessPreservesFallbackReadinessDetailsFromEngine() {
        Fixture fixture = createFixture("correct-key");

        fixture.server.expect(requestTo("http://localhost:8002/api/internal/readiness"))
                .andExpect(header("X-Internal-Api-Key", "correct-key"))
                .andRespond(withSuccess(
                        """
                                {
                                  "service":"video-llm-engine",
                                  "ready":false,
                                  "mode":"FALLBACK",
                                  "installedBackend":"mock",
                                  "realModeRequested":true,
                                  "realModelReady":false,
                                  "reason":"VIDEO_LLM_ENABLED=true but NVIDIA_API_KEY is missing; analysis will fall back to mock responses."
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(true);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "video_llm")
                .tag("outcome", "not_ready")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(result.get("response"))
                .isInstanceOfSatisfying(Map.class, response -> {
                    assertThat(response.get("mode")).isEqualTo("FALLBACK");
                    assertThat(response.get("realModelReady")).isEqualTo(false);
                    assertThat(response.get("reason").toString()).contains("NVIDIA_API_KEY is missing");
                });

        fixture.server.verify();
    }

    @Test
    void checkReadinessReportsReachableButNotAuthenticatedWhenKeyIsWrong() {
        Fixture fixture = createFixture("wrong-key");

        fixture.server.expect(requestTo("http://localhost:8002/api/internal/readiness"))
                .andRespond(withUnauthorizedRequest());

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(false);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "video_llm")
                .tag("outcome", "unauthenticated")
                .counter()
                .count()).isEqualTo(1.0);

        fixture.server.verify();
    }

    @Test
    void checkReadinessReportsUnreachableWhenEngineDoesNotRespond() {
        Fixture fixture = createFixture("correct-key");

        fixture.server.expect(requestTo("http://localhost:8002/api/internal/readiness"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(false);
        assertThat(result.get("authenticated")).isEqualTo(false);
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(fixture.registry.get("engine.readiness.check")
                .tag("engine", "video_llm")
                .tag("outcome", "unreachable")
                .counter()
                .count()).isEqualTo(1.0);

        fixture.server.verify();
    }

    private Fixture createFixture(String apiKey) {
        VideoLlmEngineProperties properties = new VideoLlmEngineProperties("http://localhost:8002", apiKey);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VideoLlmEngineClient client = new VideoLlmEngineClient(builder, properties, registry, new ObjectMapper());

        return new Fixture(client, server, registry);
    }

    private record Fixture(
            VideoLlmEngineClient client,
            MockRestServiceServer server,
            SimpleMeterRegistry registry
    ) {
    }
}
