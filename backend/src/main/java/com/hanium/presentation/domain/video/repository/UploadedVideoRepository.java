package com.hanium.presentation.domain.video.repository;

import com.hanium.presentation.domain.video.entity.UploadedVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadedVideoRepository extends JpaRepository<UploadedVideo, Long> {

    Optional<UploadedVideo> findByJobId(String jobId);

    List<UploadedVideo> findAllByJobIdIn(List<String> jobIds);

    boolean existsByJobId(String jobId);
}
