package com.hanium.presentation.global.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesAValidClientRequestIdAcrossMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "trace-123:span_456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY))
                        .isEqualTo("trace-123:span_456")
        );

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo("trace-123:span_456");
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesAnUnsafeOrOversizedClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                "unsafe value " + "x".repeat(RequestIdFilter.MAX_REQUEST_ID_LENGTH)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
            assertThat(requestId)
                    .isNotBlank()
                    .hasSize(36)
                    .doesNotContain("unsafe value");
        });

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .hasSize(36)
                .doesNotContain("unsafe value");
    }
}
