package com.hanium.presentation.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisProgressServiceTest {

    private static final String JOB_ID = "job-progress";
    private static final String REDIS_KEY = "analysis:progress:" + JOB_ID;

    @Test
    void readsProgressObjectWithStringKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redisTemplate.opsForValue().get(REDIS_KEY))
                .thenReturn("""
                        {
                          "jobId": "job-progress",
                          "status": "RUNNING",
                          "percent": 42
                        }
                        """);
        AnalysisProgressService service = new AnalysisProgressService(
                redisTemplate,
                new ObjectMapper()
        );

        Map<String, Object> progress = service.getProgress(JOB_ID);

        assertThat(progress)
                .containsEntry("jobId", JOB_ID)
                .containsEntry("status", "RUNNING")
                .containsEntry("percent", 42);
    }

    @Test
    void returnsNullWhenCachedJsonIsMalformed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redisTemplate.opsForValue().get(REDIS_KEY)).thenReturn("{malformed");
        AnalysisProgressService service = new AnalysisProgressService(
                redisTemplate,
                new ObjectMapper()
        );

        assertThat(service.getProgress(JOB_ID)).isNull();
    }
}
