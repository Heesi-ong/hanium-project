package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.ApiDocsProperties;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import com.hanium.presentation.global.properties.VideoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfilePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "DB_HOST=localhost",
                    "DB_NAME=hanium",
                    "DB_USERNAME=hanium",
                    "DB_PASSWORD=secret"
            )
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void prodProfileHardensApiDocsVideoProbeAndObjectStorageByDefault() {
        contextRunner.run(context -> {
            ApiDocsProperties apiDocsProperties = context.getBean(ApiDocsProperties.class);
            VideoProperties videoProperties = context.getBean(VideoProperties.class);
            ObjectStoragePolicyProperties objectStoragePolicyProperties =
                    context.getBean(ObjectStoragePolicyProperties.class);

            assertThat(context.getEnvironment().getProperty("security.jwt.cookie-secure", Boolean.class)).isTrue();
            assertThat(apiDocsProperties.publicEnabled()).isFalse();
            assertThat(videoProperties.durationProbeRequired()).isTrue();
            assertThat(objectStoragePolicyProperties.writeRequired()).isTrue();
            assertThat(objectStoragePolicyProperties.readPreferred()).isTrue();
        });
    }

    @EnableConfigurationProperties({
            ApiDocsProperties.class,
            VideoProperties.class,
            ObjectStoragePolicyProperties.class
    })
    static class PropertiesConfiguration {
    }
}
