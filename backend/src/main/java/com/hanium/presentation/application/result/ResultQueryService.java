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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;

    public ResultQueryService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage,
            MeterRegistry meterRegistry
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public AnalysisResultResponse getFinalResult(String jobId, Long ownerId) {
        AnalysisJob analysisJob = validateOwnership(jobId, ownerId);

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);

        Map<String, Object> result;
        try {
            result = jsonFileStorage.readJson(finalResultPath, Map.class);
        } catch (RuntimeException exception) {
            if (analysisJob.getStatus() == AnalysisStatus.COMPLETED) {
                recordDataIssue("detail", "RESULT_DATA_UNAVAILABLE");
                log.warn(
                        "[{}] 완료된 작업의 결과 상세 파일을 읽지 못해 손상 응답으로 반환합니다. reason={}",
                        jobId,
                        exception.toString()
                );
                return AnalysisResultResponse.resultDataUnavailable(jobId);
            }

            throw exception;
        }

        if (analysisJob.getStatus() == AnalysisStatus.COMPLETED && (result == null || result.isEmpty())) {
            recordDataIssue("detail", "RESULT_DATA_UNAVAILABLE");
            log.warn(
                    "[{}] 완료된 작업의 결과 상세 파일이 비어 있어 손상 응답으로 반환합니다.",
                    jobId
            );
            return AnalysisResultResponse.resultDataUnavailable(jobId);
        }

        AnalysisResultResponse response = AnalysisResultResponse.of(jobId, result);

        if (response.dataIssue() != null) {
            recordDataIssue("detail", response.dataIssue());
            log.warn(
                    "[{}] 결과 상세 조회 중 손상 신호를 감지했습니다. dataIssue={}",
                    jobId,
                    response.dataIssue()
            );
        }

        return response;
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
    // 결과 파일을 찾지 못하거나 읽지 못한 작업은 RESULT_DATA_UNAVAILABLE, 결과 파일은 있지만
    // 점수/피드백이 placeholder인 작업은 RESULT_DATA_INCOMPLETE로 표시해 나머지와 함께 반환하고,
    // 운영자가 원인을 조사할 수 있도록 jobId와 dataIssue를 로그로 남깁니다.
    // QUEUED/RUNNING 상태는 아직 결과 파일이 없는 게 정상이므로 이 검사 대상이 아닙니다.
    // 개별 상세 조회(getFinalResult)도 COMPLETED 작업의 결과 파일 손상은 dataIssue로 반환합니다.
    private ResultSummaryResponse toSummaryResponse(
            AnalysisJob analysisJob,
            Map<String, UploadedVideo> uploadedVideosByJobId
    ) {
        UploadedVideo uploadedVideo = uploadedVideosByJobId.get(analysisJob.getJobId());
        Map<String, Object> finalResult = readFinalResultSafely(analysisJob.getJobId());

        if (uploadedVideo == null) {
            recordDataIssue("list", "MISSING_VIDEO");
            log.warn(
                    "[{}] 결과 목록 조회 중 업로드 영상 정보를 찾지 못해 손상 항목으로 표시합니다.",
                    analysisJob.getJobId()
            );

            return ResultSummaryResponse.missingVideo(analysisJob, finalResult);
        }

        if (analysisJob.getStatus() == AnalysisStatus.COMPLETED && finalResult.isEmpty()) {
            recordDataIssue("list", "RESULT_DATA_UNAVAILABLE");
            log.warn(
                    "[{}] 완료된 작업의 결과 파일을 찾지 못하거나 읽지 못해 손상 항목으로 표시합니다.",
                    analysisJob.getJobId()
            );

            return ResultSummaryResponse.missingResultData(analysisJob, uploadedVideo);
        }

        ResultSummaryResponse response = ResultSummaryResponse.of(
                analysisJob,
                uploadedVideo,
                finalResult
        );

        if (response.dataIssue() != null) {
            recordDataIssue("list", response.dataIssue());
            log.warn(
                    "[{}] 결과 목록 조회 중 손상 신호를 감지했습니다. dataIssue={}",
                    analysisJob.getJobId(),
                    response.dataIssue()
            );
        }

        return response;
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

    private void recordDataIssue(String source, String issue) {
        Counter.builder("result.data_issue")
                .description("결과 조회 중 감지된 데이터 정합성 문제 건수")
                .tag("source", source)
                .tag("issue", issue)
                .register(meterRegistry)
                .increment();
    }

    private AnalysisJob validateOwnership(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        return analysisJob;
    }
}
