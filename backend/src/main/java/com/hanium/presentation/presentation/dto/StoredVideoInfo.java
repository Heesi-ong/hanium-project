package com.hanium.presentation.application.video.dto;

import com.hanium.presentation.domain.video.type.VideoFileType;

public record StoredVideoInfo(
        String originalFileName,
        String storedFilePath,
        VideoFileType fileType,
        Long fileSize
) {
}