package com.hanium.presentation.infrastructure.client.videollm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;

// 실사용 중(2026-09-01) 실제 Video LLM 재분석이 NVIDIA 쪽 일시적 503으로 실패했을 때,
// 사용자 화면(failReason)에 "502 Bad Gateway: {\"detail\":{...}}" 같은 원시 예외 텍스트가
// 그대로 노출된 문제를 확인하고 고쳤다. 이 테스트는 그 정리된 메시지 생성을 검증한다.
class VideoLlmEngineClientErrorMessageTest {

    @Test
    void analyzeSurfacesUpstreamDetailMessageInsteadOfRawExceptionText() {
        Fixture fixture = createFixture();

        fixture.server.expect(requestTo("http://localhost:8002/api/video-llm/analyze"))
                .andRespond(withRawStatus(HttpStatus.BAD_GATEWAY.value())
                        .body("""
                                {"detail":{"code":"VIDEO_LLM_REAL_MODEL_FAILED","message":"실제 Video LLM 분석에 실패했습니다."}}
                                """)
                        .contentType(MediaType.APPLICATION_JSON));

        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                () -> fixture.client.analyze(
                        VideoLlmEngineRequest.defaultOption("job-1", "/tmp/video.mp4")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_ENGINE_ERROR);
        assertThat(exception.getMessage())
                .isEqualTo("Video LLM 분석에 실패했습니다: 실제 Video LLM 분석에 실패했습니다.")
                .doesNotContain("502 Bad Gateway")
                .doesNotContain("{\"detail\"");

        fixture.server.verify();
    }

    @Test
    void analyzeFallsBackToGenericMessageWhenUpstreamBodyIsNotParseableJson() {
        Fixture fixture = createFixture();

        fixture.server.expect(requestTo("http://localhost:8002/api/video-llm/analyze"))
                .andRespond(withRawStatus(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .body("<html>upstream is down</html>")
                        .contentType(MediaType.TEXT_HTML));

        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                () -> fixture.client.analyze(
                        VideoLlmEngineRequest.defaultOption("job-1", "/tmp/video.mp4")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_ENGINE_ERROR);
        assertThat(exception.getMessage())
                .isEqualTo("Video LLM 엔진이 오류를 반환했습니다(HTTP 503). 잠시 후 다시 시도해주세요.")
                .doesNotContain("<html>");

        fixture.server.verify();
    }

    private Fixture createFixture() {
        VideoLlmEngineProperties properties = new VideoLlmEngineProperties("http://localhost:8002", "test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VideoLlmEngineClient client = new VideoLlmEngineClient(
                builder, properties, new SimpleMeterRegistry(), new ObjectMapper()
        );

        return new Fixture(client, server);
    }

    private record Fixture(
            VideoLlmEngineClient client,
            MockRestServiceServer server
    ) {
    }
}
