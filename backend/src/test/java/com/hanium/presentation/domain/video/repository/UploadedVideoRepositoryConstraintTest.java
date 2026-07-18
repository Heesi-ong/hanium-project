package com.hanium.presentation.domain.video.repository;

import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.type.VideoFileType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UploadedVideoRepositoryConstraintTest {

    @Autowired
    private UploadedVideoRepository uploadedVideoRepository;

    @Test
    void rejectsDuplicateJobId() {
        uploadedVideoRepository.save(UploadedVideo.create(
                "job-duplicate-video",
                "first.mp4",
                "/storage/uploads/job-duplicate-video/first.mp4",
                VideoFileType.MP4,
                1024L
        ));

        assertThatThrownBy(() -> {
            uploadedVideoRepository.saveAndFlush(UploadedVideo.create(
                    "job-duplicate-video",
                    "second.mp4",
                    "/storage/uploads/job-duplicate-video/second.mp4",
                    VideoFileType.MP4,
                    2048L
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
