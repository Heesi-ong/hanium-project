package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.OpenAiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiApiKeyStartupValidatorTest {

    @Test
    void rejectsEnabledWithBlankApiKey() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("");

        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(properties);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void rejectsEnabledWithNullApiKey() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey(null);

        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(properties);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void acceptsDisabledWithBlankApiKey() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(false);
        properties.setApiKey("");

        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(properties);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsEnabledWithRealApiKey() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-a-real-key-value");

        OpenAiApiKeyStartupValidator validator = new OpenAiApiKeyStartupValidator(properties);

        assertThatCode(validator::validate)
                .doesNotThrowAnyException();
    }
}
