package com.hanium.presentation.global.security;

import com.hanium.presentation.global.properties.ClientIpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedForByDefault() {
        ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }

    @Test
    void usesFirstForwardedForValueWhenTrusted() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustForwardedHeaders(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToRemoteAddrWhenTrustedHeaderIsBlank() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustForwardedHeaders(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", " , ");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }
}
