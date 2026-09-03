package com.hanium.presentation.application.result;

import com.hanium.presentation.application.storage.StorageDeletionTaskService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Service
public class ResultCommandService {

    private static final Logger log = LoggerFactory.getLogger(ResultCommandService.class);

    private final ResultMergeService resultMergeService;
    private final AnalysisCompactor analysisCompactor;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;
    private final LocalFileStorage localFileStorage;
    private final AnalysisFrameOverlayStorage analysisFrameOverlayStorage;
    private final StorageDeletionTaskService storageDeletionTaskService;
    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;

    public ResultCommandService(
            ResultMergeService resultMergeService,
            AnalysisCompactor analysisCompactor,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage,
            LocalFileStorage localFileStorage,
            AnalysisFrameOverlayStorage analysisFrameOverlayStorage,
            StorageDeletionTaskService storageDeletionTaskService,
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository
    ) {
        this.resultMergeService = resultMergeService;
        this.analysisCompactor = analysisCompactor;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
        this.localFileStorage = localFileStorage;
        this.analysisFrameOverlayStorage = analysisFrameOverlayStorage;
        this.storageDeletionTaskService = storageDeletionTaskService;
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
    }

    /**
     * 분석 엔진 응답의 오버레이 프레임을 결과 스토리지에 저장하고, base64를 비운 대신
     * 갤러리 메타데이터를 채운 응답 사본을 반환합니다. 오버레이가 없으면 원본을 그대로
     * 돌려줍니다.
     */
    public AnalysisEngineResponse persistFrameOverlays(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse
    ) {
        if (analysisEngineResponse == null) {
            return null;
        }

        return analysisEngineResponse.withPersistedFrameGallery(
                analysisFrameOverlayStorage.persist(jobId, analysisEngineResponse)
        );
    }

    public void saveAnalysisEngineResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Path basicAnalysisPath = filePathGenerator.generateBasicAnalysisPath(jobId);
        jsonFileStorage.saveJson(basicAnalysisPath, analysisEngineResponse);
    }

    public void saveVideoLlmRawResult(
            String jobId,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Path videoLlmRawPath = filePathGenerator.generateVideoLlmRawPath(jobId);
        jsonFileStorage.saveJson(videoLlmRawPath, videoLlmEngineResponse);
    }

    public Map<String, Object> saveVideoLlmCompactResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> compactResult = analysisCompactor.compact(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse
        );

        Path videoLlmCompactPath = filePathGenerator.generateVideoLlmCompactPath(jobId);
        jsonFileStorage.saveJson(videoLlmCompactPath, compactResult);

        Path compactAnalysisPath = filePathGenerator.generateCompactAnalysisPath(jobId);
        jsonFileStorage.saveJson(compactAnalysisPath, compactResult);

        return compactResult;
    }

    public void saveOpenAiFeedbackResult(
            String jobId,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Path openAiFeedbackPath = filePathGenerator.generateOpenAiFeedbackPath(jobId);
        jsonFileStorage.saveJson(openAiFeedbackPath, openAiFeedbackResponse);
    }

    public Optional<OpenAiFeedbackResponse> loadExistingRealOpenAiFeedback(String jobId) {
        Path openAiFeedbackPath = filePathGenerator.generateOpenAiFeedbackPath(jobId);

        if (!Files.exists(openAiFeedbackPath)) {
            return Optional.empty();
        }

        try {
            OpenAiFeedbackResponse openAiFeedbackResponse = jsonFileStorage.readJson(
                    openAiFeedbackPath,
                    OpenAiFeedbackResponse.class
            );

            if ("REAL".equals(openAiFeedbackResponse.generationMode())) {
                return Optional.of(openAiFeedbackResponse);
            }
        } catch (BusinessException exception) {
            log.warn(
                    "[{}] 기존 OpenAI 피드백 파일을 읽지 못해 재사용하지 않습니다. path={}, reason={}",
                    jobId,
                    openAiFeedbackPath,
                    exception.getMessage()
            );
        }

        return Optional.empty();
    }

    public void saveFinalResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse,
                openAiFeedbackResponse
        );

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
        jsonFileStorage.saveJson(finalResultPath, finalResult);
    }

    public void saveFailureResult(
            String jobId,
            String failedStep,
            String failReason
    ) {
        Map<String, Object> failureResult = resultMergeService.createFailureResult(
                jobId,
                failedStep,
                failReason
        );

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
        jsonFileStorage.saveJson(finalResultPath, failureResult);
    }

    public Map<String, Object> saveEngineResultsAndCompact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        saveAnalysisEngineResult(jobId, analysisEngineResponse);
        saveVideoLlmRawResult(jobId, videoLlmEngineResponse);

        return saveVideoLlmCompactResult(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse
        );
    }

    @Transactional
    public void updateMemo(String jobId, Long ownerId, String memo) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        String normalizedMemo = memo == null || memo.isBlank() ? null : memo.trim();
        analysisJob.updateMemo(normalizedMemo);
    }

    @Transactional
    public void deleteResult(String jobId, Long ownerId) {
        // 재분석 접수도 source job 행을 잠그므로, 결과 삭제와 child 생성이 같은 source에서
        // 동시에 진행돼 "마지막 참조" 판정 뒤 새 child가 생기는 race를 막습니다.
        AnalysisJob analysisJob = analysisJobRepository.findByJobIdForUpdate(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        if (analysisJob.isQueued() || analysisJob.isRunning()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED,
                    "진행 중이거나 대기 중인 분석 작업은 삭제할 수 없습니다. 먼저 취소한 뒤 다시 시도해주세요. status=" + analysisJob.getStatus()
            );
        }

        // source를 먼저 지우면 자식의 source_job_id가 더 이상 존재하지 않는 job을 가리켜
        // lineage 링크가 깨지고, 실행 중인 재분석은 source 검증 계약도 잃는다. 재분석 생성과
        // 마찬가지로 source 행을 잠근 상태에서 참조 존재 여부를 확인하므로, 이 판정 뒤 새
        // child가 끼어드는 race도 없다. 회원탈퇴는 createdAt 내림차순으로 삭제해 최신 child가
        // source보다 먼저 처리되므로 전체 삭제 흐름은 유지된다.
        if (analysisJobRepository.existsBySourceJobId(jobId)) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED,
                    "이 결과를 원본으로 사용하는 재분석 결과가 남아 있습니다. 재분석 결과를 먼저 삭제한 뒤 다시 시도해주세요."
            );
        }

        Long videoAssetId = analysisJob.getVideoAssetId();
        UploadedVideo videoAsset = videoAssetId == null
                ? uploadedVideoRepository.findByJobIdForUpdate(jobId).orElse(null)
                : uploadedVideoRepository.findByIdForUpdate(videoAssetId)
                        .or(() -> uploadedVideoRepository.findByJobIdForUpdate(jobId))
                        .orElse(null);
        String videoStorageJobId = videoAsset == null ? jobId : videoAsset.getJobId();
        analysisJobRepository.delete(analysisJob);
        analysisJobRepository.flush();

        boolean deleteVideoAsset = videoAssetId == null
                || analysisJobRepository.countByVideoAssetId(videoAssetId) == 0;
        if (deleteVideoAsset && videoAsset != null) {
            uploadedVideoRepository.delete(videoAsset);
        }

        // MinIO 프리픽스 삭제는 outbox(StorageDeletionTask) 행을 만드는 것으로 대신하고,
        // 이 행을 지금 이 트랜잭션 안에서(=업무 데이터 삭제와 원자적으로) 커밋합니다. 실제
        // 오브젝트 삭제는 StorageDeletionOutboxWorker가 요청과 무관하게 재시도하며 수행하므로,
        // 요청 처리 중 MinIO가 느리거나 실패해도 삭제 자체가 유실되지 않습니다
        // (2026-07-23 코드 리뷰 P1-03 — 이전에는 best-effort 호출 실패 시 로그만 남기고
        // 다시 시도할 방법이 없었습니다).
        if (deleteVideoAsset) {
            storageDeletionTaskService.enqueue(
                    videoStorageJobId,
                    "uploads/" + videoStorageJobId + "/",
                    StorageDeletionReason.RESULT_DELETE
            );
        }
        storageDeletionTaskService.enqueue(jobId, "results/" + jobId + "/", StorageDeletionReason.RESULT_DELETE);

        // 로컬 디스크 파일 삭제는 이 트랜잭션이 커밋된 뒤에만 실행합니다. 회원탈퇴처럼 여러 job을
        // 한 트랜잭션 안에서 순회 삭제하다가 뒤에 나온 job이 실패해 전체가 롤백되면, 커밋
        // 전에 파일부터 지웠을 경우 이미 처리된 앞선 job들의 물리 파일은 사라졌는데 DB
        // 행만 롤백으로 되살아나는 불일치가 생깁니다(실제 재현된 문제). 파일 삭제를
        // 커밋 이후로 미루면 트랜잭션이 롤백될 때 파일 삭제 자체가 실행되지 않습니다.
        scheduleLocalFileDeletionAfterCommit(
                jobId,
                deleteVideoAsset ? videoStorageJobId : null
        );
    }

    private void scheduleLocalFileDeletionAfterCommit(
            String resultJobId,
            String videoStorageJobId
    ) {
        Runnable deleteFiles = () -> {
            try {
                if (videoStorageJobId != null) {
                    Path uploadDirectory = filePathGenerator.generateUploadDirectory(videoStorageJobId);
                    localFileStorage.deleteDirectoryIfExists(uploadDirectory);
                }
                Path resultDirectory = filePathGenerator.generateResultDirectory(resultJobId);

                localFileStorage.deleteDirectoryIfExists(resultDirectory);
            } catch (Exception exception) {
                // 이 시점에는 DB 삭제가 이미 커밋되어 되돌릴 수 없으므로, 로컬 파일 삭제
                // 실패는 예외를 던지는 대신 로그만 남깁니다(고아 파일이 남을 뿐 DB와의
                // 불일치로 이어지지는 않습니다). 로컬 고아 디렉터리는 StorageCleanupService가
                // 별도로 발견해 정리합니다.
                log.warn(
                        "RESULT_DELETE_LOCAL_FILE_FAILED_AFTER_COMMIT jobId={} videoStorageJobId={} reason={}",
                        resultJobId,
                        videoStorageJobId,
                        exception.toString()
                );
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFiles.run();
                }
            });
        } else {
            // 트랜잭션 없이 호출되는 경우(예: 단위 테스트)를 대비한 안전장치입니다.
            deleteFiles.run();
        }
    }
}
