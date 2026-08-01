package com.hanium.presentation.presentation.dto.response;

public record ServiceStatusResponse(
        Availability overallStatus,
        ComponentStatus backend,
        ComponentStatus analysisEngine,
        ComponentStatus videoLlmEngine,
        ComponentStatus passwordReset
) {

    public enum Availability {
        AVAILABLE,
        DEGRADED,
        UNAVAILABLE
    }

    public record ComponentStatus(
            Availability status,
            String message
    ) {
    }
}
