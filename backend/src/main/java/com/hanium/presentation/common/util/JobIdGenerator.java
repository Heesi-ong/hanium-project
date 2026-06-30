package com.hanium.presentation.common.util;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class JobIdGenerator {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String generate() {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String randomValue = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);

        return timestamp + "-" + randomValue;
    }
}