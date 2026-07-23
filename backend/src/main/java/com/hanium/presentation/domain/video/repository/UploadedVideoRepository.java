package com.hanium.presentation.domain.video.repository;

import com.hanium.presentation.domain.video.entity.UploadedVideo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UploadedVideoRepository extends JpaRepository<UploadedVideo, Long> {

    Optional<UploadedVideo> findByJobId(String jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM UploadedVideo v WHERE v.id = :id")
    Optional<UploadedVideo> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM UploadedVideo v WHERE v.jobId = :jobId")
    Optional<UploadedVideo> findByJobIdForUpdate(@Param("jobId") String jobId);

    List<UploadedVideo> findAllByJobIdIn(List<String> jobIds);

    boolean existsByJobId(String jobId);
}
