package com.hanium.presentation.infrastructure.client.videollm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.logging.RequestIdFilter;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// VideoLlmEngineClient.analyze()가 MDC의 requestId를 X-Request-Id 헤더로 실제로
// 전달하는지(그리고 없을 때는 헤더 자체를 생략하는지) 검증한다. AnalysisEngineClient와
// 동일한 코드 경로이며 마찬가지로 커밋 시점에 자동화 테스트가 없었다.
class VideoLlmEngineClientRequestIdTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void analyzeForwardsRequestIdHeaderWhenPresentInMdc() {
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "req-xyz-789");

        Fixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8002/api/video-llm/analyze"))
                .andExpect(header(RequestIdFilter.REQUEST_ID_HEADER, "req-xyz-789"))
                .andRespond(withSuccess(
                        "{\"jobId\":\"job-1\",\"status\":\"success\",\"model\":{\"generationMode\":\"MOCK\"}}",
                        MediaType.APPLICATION_JSON
                ));

        fixture.client.analyze(VideoLlmEngineRequest.defaultOption("job-1", "/tmp/video.mp4"));

        fixture.server.verify();
    }

    @Test
    void analyzeOmitsRequestIdHeaderWhenNotInMdc() {
        Fixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8002/api/video-llm/analyze"))
                .andExpect(headerDoesNotExist(RequestIdFilter.REQUEST_ID_HEADER))
                .andRespond(withSuccess(
                        "{\"jobId\":\"job-1\",\"status\":\"success\",\"model\":{\"generationMode\":\"MOCK\"}}",
                        MediaType.APPLICATION_JSON
                ));

        fixture.client.analyze(VideoLlmEngineRequest.defaultOption("job-1", "/tmp/video.mp4"));

        fixture.server.verify();
    }

    @Test
    void analyzeTreatsStrictPolicyBadGatewayAsEngineFailure() {
        Fixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8002/api/video-llm/analyze"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "detail": {
                                    "code": "VIDEO_LLM_REAL_MODEL_FAILED",
                                    "message": "실제 Video LLM 분석에 실패했습니다."
                                  }
                                }
                                """));

        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                () -> fixture.client.analyze(
                        VideoLlmEngineRequest.defaultOption("job-strict", "/tmp/video.mp4")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_ENGINE_ERROR);
        fixture.server.verify();
    }

    private Fixture createFixture() {
        VideoLlmEngineProperties properties = new VideoLlmEngineProperties("http://localhost:8002", "test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VideoLlmEngineClient client = new VideoLlmEngineClient(builder, properties, new SimpleMeterRegistry(), new ObjectMapper());

        return new Fixture(client, server);
    }

    private record Fixture(
            VideoLlmEngineClient client,
            MockRestServiceServer server
    ) {
    }
}
