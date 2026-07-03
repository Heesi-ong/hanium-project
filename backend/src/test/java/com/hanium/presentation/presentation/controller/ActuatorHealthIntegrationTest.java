package com.hanium.presentation.presentation.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.redis.enabled=false"
)
class ActuatorHealthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void actuatorHealthIsPublicAndOtherActuatorEndpointsAreNotExposed() {
        ResponseEntity<String> healthResponse = restTemplate.getForEntity(
                "/actuator/health",
                String.class
        );

        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).contains("\"status\"");

        ResponseEntity<String> envResponse = restTemplate.getForEntity(
                "/actuator/env",
                String.class
        );

        assertThat(envResponse.getStatusCode())
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    void existingApiHealthEndpointStillWorksWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/health",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
