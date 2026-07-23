package com.hanium.presentation.domain.analysis.type;

import java.util.Locale;

public enum VideoLlmGenerationMode {
    REAL,
    FALLBACK,
    MOCK,
    SKIPPED,
    UNKNOWN;

    public static VideoLlmGenerationMode from(Object rawMode) {
        if (rawMode == null) {
            return UNKNOWN;
        }

        try {
            return valueOf(rawMode.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
