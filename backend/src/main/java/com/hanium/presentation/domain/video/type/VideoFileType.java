package com.hanium.presentation.domain.video.type;

import java.util.Arrays;

public enum VideoFileType {

    MP4(".mp4"),
    MOV(".mov"),
    AVI(".avi"),
    MKV(".mkv");

    private final String extension;

    VideoFileType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }

    public static boolean isSupported(String extension) {
        if (extension == null) {
            return false;
        }

        return Arrays.stream(values())
                .anyMatch(type -> type.extension.equalsIgnoreCase(extension));
    }

    public static VideoFileType fromExtension(String extension) {
        if (extension == null) {
            throw new IllegalArgumentException("파일 확장자가 없습니다.");
        }

        return Arrays.stream(values())
                .filter(type -> type.extension.equalsIgnoreCase(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 영상 파일 형식입니다."));
    }
}