package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.logging.RequestIdFilter;
import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// AnalysisEngineClient.analyze()가 MDC의 requestId를 X-Request-Id 헤더로 실제로
// 전달하는지(그리고 없을 때는 헤더 자체를 생략하는지) 검증한다. 이 코드 경로는
// 커밋 시점에 자동화 테스트 없이 추가됐던 부분이다.
class AnalysisEngineClientRequestIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void analyzeForwardsRequestIdHeaderWhenPresentInMdc() {
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "req-abc-123");

        Fixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8001/api/basic-analysis"))
                .andExpect(header(RequestIdFilter.REQUEST_ID_HEADER, "req-abc-123"))
                .andRespond(withSuccess(
                        "{\"jobId\":\"job-1\",\"status\":\"success\"}",
                        MediaType.APPLICATION_JSON
                ));

        fixture.client.analyze(new AnalysisEngineRequest("job-1", "/tmp/video.mp4"));

        fixture.server.verify();
    }

    @Test
    void analyzeOmitsRequestIdHeaderWhenNotInMdc() {
        Fixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8001/api/basic-analysis"))
                .andExpect(headerDoesNotExist(RequestIdFilter.REQUEST_ID_HEADER))
                .andRespond(withSuccess(
                        "{\"jobId\":\"job-1\",\"status\":\"success\"}",
                        MediaType.APPLICATION_JSON
                ));

        fixture.client.analyze(new AnalysisEngineRequest("job-1", "/tmp/video.mp4"));

        fixture.server.verify();
    }

    private Fixture createFixture() {
        AnalysisEngineProperties properties = new AnalysisEngineProperties("http://localhost:8001", "test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisEngineClient client = new AnalysisEngineClient(builder, properties, new SimpleMeterRegistry());

        return new Fixture(client, server);
    }

    private record Fixture(
            AnalysisEngineClient client,
            MockRestServiceServer server
    ) {
    }
}
