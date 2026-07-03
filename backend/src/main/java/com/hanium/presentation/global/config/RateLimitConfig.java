package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.RateLimitProperties;
import com.hanium.presentation.global.filter.UserRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitConfig {

    @Bean
    public UserRateLimiter userRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitProperties rateLimitProperties
    ) {
        return new UserRateLimiter(redisTemplate, rateLimitProperties);
    }

    @Bean
    public UserRateLimitFilter userRateLimitFilter(
            UserRateLimiter userRateLimiter,
            ObjectMapper objectMapper
    ) {
        return new UserRateLimitFilter(userRateLimiter, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<UserRateLimitFilter> userRateLimitFilterRegistration(
            UserRateLimitFilter userRateLimitFilter
    ) {
        FilterRegistrationBean<UserRateLimitFilter> registration = new FilterRegistrationBean<>(userRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}
