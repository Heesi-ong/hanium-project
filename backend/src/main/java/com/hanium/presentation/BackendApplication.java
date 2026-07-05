package com.hanium.presentation;

import com.hanium.presentation.global.properties.AnalysisEngineProperties;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import com.hanium.presentation.global.properties.OpenAiProperties;
import com.hanium.presentation.global.properties.RateLimitProperties;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.global.properties.VideoProperties;
import com.hanium.presentation.global.properties.VideoLlmEngineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({
        StorageProperties.class,
        AnalysisEngineProperties.class,
        VideoLlmEngineProperties.class,
        OpenAiProperties.class,
        RateLimitProperties.class,
        AnalysisRetryProperties.class,
        VideoProperties.class
})
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
