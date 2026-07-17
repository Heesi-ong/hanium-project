package com.hanium.presentation.infrastructure.client.videollm;

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
    void checkReadinessReportsReachableAuthenticatedAndReadyOnSuccess() {
        Fixture fixture = createFixture("correct-key");

        fixture.server.expect(requestTo("http://localhost:8002/api/internal/readiness"))
                .andExpect(header("X-Internal-Api-Key", "correct-key"))
                .andRespond(withSuccess(
                        "{\"service\":\"video-llm-engine\",\"ready\":true,\"mode\":\"MOCK\"}",
                        MediaType.APPLICATION_JSON
                ));

        Map<String, Object> result = fixture.client.checkReadiness();

        assertThat(result.get("reachable")).isEqualTo(true);
        assertThat(result.get("authenticated")).isEqualTo(true);
        assertThat(result.get("ready")).isEqualTo(true);

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

        fixture.server.verify();
    }

    private Fixture createFixture(String apiKey) {
        VideoLlmEngineProperties properties = new VideoLlmEngineProperties("http://localhost:8002", apiKey);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VideoLlmEngineClient client = new VideoLlmEngineClient(builder, properties, new SimpleMeterRegistry());

        return new Fixture(client, server);
    }

    private record Fixture(
            VideoLlmEngineClient client,
            MockRestServiceServer server
    ) {
    }
}
