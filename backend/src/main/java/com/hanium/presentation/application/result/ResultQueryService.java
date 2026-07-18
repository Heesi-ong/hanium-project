package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.Map;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ResultQueryService {

    private static final Logger log = LoggerFactory.getLogger(ResultQueryService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;

    public ResultQueryService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
    }

    @Transactional(readOnly = true)
    public AnalysisResultResponse getFinalResult(String jobId, Long ownerId) {
        validateOwnership(jobId, ownerId);

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);

        Map<String, Object> result = jsonFileStorage.readJson(finalResultPath, Map.class);

        return AnalysisResultResponse.of(jobId, result);
    }

    @Transactional(readOnly = true)
    public Page<ResultSummaryResponse> getResultSummaries(Long ownerId, Pageable pageable) {
        Page<AnalysisJob> analysisJobs = analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(
                ownerId,
                pageable
        );
        Map<String, UploadedVideo> uploadedVideosByJobId = getUploadedVideosByJobId(
                analysisJobs.getContent()
        );

        return analysisJobs.map(analysisJob -> toSummaryResponse(
                analysisJob,
                uploadedVideosByJobId
        ));
    }

    private Map<String, UploadedVideo> getUploadedVideosByJobId(List<AnalysisJob> analysisJobs) {
        List<String> jobIds = analysisJobs.stream()
                .map(AnalysisJob::getJobId)
                .toList();

        if (jobIds.isEmpty()) {
            return Map.of();
        }

        return uploadedVideoRepository.findAllByJobIdIn(jobIds).stream()
                .collect(Collectors.toMap(
                        UploadedVideo::getJobId,
                        Function.identity()
                ));
    }

    // 목록 조회는 한 작업의 데이터 정합성이 깨졌다고 전체를 실패시키지 않습니다. 업로드 영상
    // 레코드가 없는 작업은 손상 항목(dataIssue=MISSING_VIDEO)으로, 완료(COMPLETED) 처리됐는데
    // 결과 파일을 찾지 못하거나 읽지 못한 작업은 손상 항목(dataIssue=RESULT_DATA_UNAVAILABLE)으로
    // 표시해 나머지와 함께 반환하고, 운영자가 원인을 조사할 수 있도록 jobId를 로그로 남깁니다.
    // QUEUED/RUNNING 상태는 아직 결과 파일이 없는 게 정상이므로 이 검사 대상이 아닙니다.
    // 명확한 오류가 필요한 경우는 개별 상세 조회(getFinalResult)에서만 던집니다.
    private ResultSummaryResponse toSummaryResponse(
            AnalysisJob analysisJob,
            Map<String, UploadedVideo> uploadedVideosByJobId
    ) {
        UploadedVideo uploadedVideo = uploadedVideosByJobId.get(analysisJob.getJobId());
        Map<String, Object> finalResult = readFinalResultSafely(analysisJob.getJobId());

        if (uploadedVideo == null) {
            log.warn(
                    "[{}] 결과 목록 조회 중 업로드 영상 정보를 찾지 못해 손상 항목으로 표시합니다.",
                    analysisJob.getJobId()
            );

            return ResultSummaryResponse.missingVideo(analysisJob, finalResult);
        }

        if (analysisJob.getStatus() == AnalysisStatus.COMPLETED && finalResult.isEmpty()) {
            log.warn(
                    "[{}] 완료된 작업의 결과 파일을 찾지 못하거나 읽지 못해 손상 항목으로 표시합니다.",
                    analysisJob.getJobId()
            );

            return ResultSummaryResponse.missingResultData(analysisJob, uploadedVideo);
        }

        return ResultSummaryResponse.of(
                analysisJob,
                uploadedVideo,
                finalResult
        );
    }

    private Map<String, Object> readFinalResultSafely(String jobId) {
        try {
            Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
            Map<String, Object> finalResult = jsonFileStorage.readJson(finalResultPath, Map.class);

            return finalResult == null ? Map.of() : finalResult;
        } catch (RuntimeException exception) {
            log.warn(
                    "[{}] 결과 목록 조회 중 최종 결과 파일을 읽지 못했습니다. reason={}",
                    jobId,
                    exception.toString()
            );
            return Map.of();
        }
    }

    private void validateOwnership(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }
    }
}
