package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalEngineApiKeyStartupValidatorTest {

    @Test
    void rejectsBlankAnalysisEngineApiKey() {
        InternalEngineApiKeyStartupValidator validator = new InternalEngineApiKeyStartupValidator(
                new AnalysisEngineProperties("http://localhost:8001", ""),
                new VideoLlmEngineProperties("http://localhost:8002", "shared-key")
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_ENGINE_API_KEY");
    }

    @Test
    void rejectsNullVideoLlmEngineApiKey() {
        InternalEngineApiKeyStartupValidator validator = new InternalEngineApiKeyStartupValidator(
                new AnalysisEngineProperties("http://localhost:8001", "shared-key"),
                new VideoLlmEngineProperties("http://localhost:8002", null)
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_ENGINE_API_KEY");
    }

    @Test
    void rejectsBlankWhitespaceOnlyApiKey() {
        InternalEngineApiKeyStartupValidator validator = new InternalEngineApiKeyStartupValidator(
                new AnalysisEngineProperties("http://localhost:8001", "   "),
                new VideoLlmEngineProperties("http://localhost:8002", "shared-key")
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsMatchingNonBlankApiKeys() {
        InternalEngineApiKeyStartupValidator validator = new InternalEngineApiKeyStartupValidator(
                new AnalysisEngineProperties("http://localhost:8001", "shared-key"),
                new VideoLlmEngineProperties("http://localhost:8002", "shared-key")
        );

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }
}
