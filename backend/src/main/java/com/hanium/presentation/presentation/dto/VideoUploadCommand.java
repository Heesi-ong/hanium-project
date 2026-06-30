package com.hanium.presentation.application.video.dto;

import org.springframework.web.multipart.MultipartFile;

public record VideoUploadCommand(
        String jobId,
        MultipartFile file
) {
}