package com.hanium.presentation.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // 쉼표로 구분된 origin 목록을 환경변수(CORS_ALLOWED_ORIGINS)로 주입받습니다.
    // 기본값은 application.yaml의 cors.allowed-origins 설정을 따릅니다.
    @Value("${cors.allowed-origins}")
    private String allowedOriginsCsv;

    private static final String[] ALLOWED_METHODS = {
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS"
    };

    private static final String[] ALLOWED_HEADERS = {
            "*"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(parseAllowedOrigins())
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders(ALLOWED_HEADERS)
                .allowCredentials(true)
                .maxAge(3600);
    }

    private String[] parseAllowedOrigins() {
        return allowedOriginsCsv.split("\\s*,\\s*");
    }
}
