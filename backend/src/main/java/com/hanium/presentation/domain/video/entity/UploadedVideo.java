package com.hanium.presentation.domain.video.entity;

import com.hanium.presentation.domain.video.type.VideoFileType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "uploaded_videos")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadedVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String jobId;

    @Column(nullable = false, length = 255)
    private String originalFileName;

    @Column(nullable = false, length = 500)
    private String storedFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoFileType fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    private UploadedVideo(
            String jobId,
            String originalFileName,
            String storedFilePath,
            VideoFileType fileType,
            Long fileSize
    ) {
        this.jobId = jobId;
        this.originalFileName = originalFileName;
        this.storedFilePath = storedFilePath;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.uploadedAt = LocalDateTime.now();
    }

    public static UploadedVideo create(
            String jobId,
            String originalFileName,
            String storedFilePath,
            VideoFileType fileType,
            Long fileSize
    ) {
        return new UploadedVideo(
                jobId,
                originalFileName,
                storedFilePath,
                fileType,
                fileSize
        );
    }
}