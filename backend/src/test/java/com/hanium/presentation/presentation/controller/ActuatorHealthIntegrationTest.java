package com.hanium.presentation.presentation.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.health.redis.enabled=false",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.prometheus.metrics.export.enabled=true"
        }
)
class ActuatorHealthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalManagementPort
    private int managementPort;

    @Test
    void actuatorEndpointsAreServedOnlyFromManagementPort() {
        ResponseEntity<String> mainPortHealthResponse = restTemplate.getForEntity(
                "/actuator/health",
                String.class
        );

        assertThat(mainPortHealthResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> healthResponse = restTemplate.getForEntity(
                managementUrl("/actuator/health"),
                String.class
        );

        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).contains("\"status\"");

        ResponseEntity<String> envResponse = restTemplate.getForEntity(
                managementUrl("/actuator/env"),
                String.class
        );

        assertThat(envResponse.getStatusCode())
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity(
                managementUrl("/actuator/prometheus"),
                String.class
        );

        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheusResponse.getBody()).containsAnyOf("# HELP", "# TYPE");
    }

    @Test
    void existingApiHealthEndpointStillWorksWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/health",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
