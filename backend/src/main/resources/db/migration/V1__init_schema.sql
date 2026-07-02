-- 초기 스키마: 현재 JPA 엔티티(AnalysisJob, UploadedVideo) 기준으로 작성했습니다.
-- 컬럼명은 Spring Boot 기본 네이밍 규칙(카멜케이스 -> 스네이크케이스)을 그대로 따릅니다.
-- dev/prod(MySQL) 프로필에서만 적용되며, local(H2) 프로필은 그대로 Hibernate 자동 생성을 사용합니다.

CREATE TABLE analysis_jobs (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- 동시 실행 요청을 막기 위한 낙관적 락 버전 컬럼 (AnalysisJob.version, @Version)
    version       BIGINT       NOT NULL DEFAULT 0,
    job_id        VARCHAR(50)  NOT NULL,
    status        VARCHAR(50)  NOT NULL,
    fail_reason   VARCHAR(500),
    created_at    DATETIME(6)  NOT NULL,
    started_at    DATETIME(6),
    completed_at  DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_jobs_job_id UNIQUE (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE uploaded_videos (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    job_id              VARCHAR(50)  NOT NULL,
    original_file_name  VARCHAR(255) NOT NULL,
    stored_file_path    VARCHAR(500) NOT NULL,
    file_type           VARCHAR(20)  NOT NULL,
    file_size           BIGINT       NOT NULL,
    uploaded_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_uploaded_videos_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
