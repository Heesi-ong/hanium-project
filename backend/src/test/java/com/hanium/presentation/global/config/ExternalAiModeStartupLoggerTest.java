package com.hanium.presentation.global.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hanium.presentation.global.properties.CoachLlmProperties;
import com.hanium.presentation.global.properties.FeedbackLlmProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAiModeStartupLoggerTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ExternalAiModeStartupLogger.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ExternalAiModeStartupLogger.class)).detachAppender(appender);
    }

    private String bannerAfterLogging(
            OpenAiProperties openAi,
            FeedbackLlmProperties feedback,
            CoachLlmProperties coach
    ) {
        new ExternalAiModeStartupLogger(openAi, feedback, coach).logExternalAiMode();
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("EXTERNAL_AI_MODE"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void logsMockWhenOpenAiDisabled() {
        String banner = bannerAfterLogging(
                new OpenAiProperties(),
                new FeedbackLlmProperties(),
                new CoachLlmProperties()
        );

        assertThat(banner)
                .contains("openaiEnabled=false")
                .contains("openaiApiKey=empty")
                .contains("feedback=mock(provider=openai)")
                .contains("coach=mock(provider=openai)");
    }

    @Test
    void logsRealWhenOpenAiEnabledWithKey() {
        OpenAiProperties openAi = new OpenAiProperties();
        openAi.setEnabled(true);
        openAi.setApiKey("sk-test");

        String banner = bannerAfterLogging(
                openAi,
                new FeedbackLlmProperties(),
                new CoachLlmProperties()
        );

        assertThat(banner)
                .contains("openaiEnabled=true")
                .contains("openaiApiKey=present")
                .contains("feedback=real(provider=openai)")
                .contains("coach=real(provider=openai)");
    }

    @Test
    void reflectsNvidiaProviderPerFeature() {
        FeedbackLlmProperties feedback = new FeedbackLlmProperties();
        feedback.setProvider("nvidia");
        feedback.setNvidiaApiKey("nv-key");

        CoachLlmProperties coach = new CoachLlmProperties();
        coach.setProvider("nvidia");
        coach.setNvidiaApiKey("");

        String banner = bannerAfterLogging(new OpenAiProperties(), feedback, coach);

        assertThat(banner)
                .contains("feedback=real(provider=nvidia)")
                .contains("coach=mock(provider=nvidia)");
    }
}
