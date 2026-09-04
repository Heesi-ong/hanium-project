import apiClient, { unwrapApiResponse } from "./apiClient";
import { validateResultSchemaResponse } from "../contracts/resultSchema";

export const UPLOAD_ANALYSIS_TIMEOUT_MS = 20 * 60 * 1000;
export const ANALYSIS_COMMAND_TIMEOUT_MS = 30 * 1000;
export const ANALYSIS_POLL_TIMEOUT_MS = 10 * 1000;

export async function getServiceStatus() {
    const response = await apiClient.get("/api/status");
    return unwrapApiResponse(response);
}

export async function uploadAnalysisVideo(
    file,
    { onUploadProgress, practiceContext } = {}
) {
    const formData = new FormData();
    formData.append("file", file);
    if (practiceContext?.baselineJobId && practiceContext?.practiceGoal) {
        formData.append("baselineJobId", practiceContext.baselineJobId);
        formData.append("practiceGoal", practiceContext.practiceGoal);
    }

    const response = await apiClient.post("/api/analysis/upload", formData, {
        headers: {
            "Content-Type": "multipart/form-data",
        },
        timeout: UPLOAD_ANALYSIS_TIMEOUT_MS,
        onUploadProgress,
    });

    return unwrapApiResponse(response);
}

export async function runAnalysis(jobId, options = {}) {
    const requestBody = {
        useVideoLlm: options.useVideoLlm ?? true,
        useOpenAi: options.useOpenAi ?? true,
    };

    const response = await apiClient.post(
        `/api/analysis/${jobId}/run`,
        requestBody,
        { timeout: ANALYSIS_COMMAND_TIMEOUT_MS }
    );

    return unwrapApiResponse(response);
}

export async function retryAnalysis(jobId, options) {
    // 옵션을 생략한 일반 재시도는 서버가 DB에 저장한 최초 실행 선택을 그대로 사용합니다.
    // 여기서 true/true를 기본 전송하면 사용자가 끈 외부 AI 전송·비용 옵션이 다시 켜집니다.
    const requestBody = options
        ? {
            ...(options.useVideoLlm !== undefined && {
                useVideoLlm: options.useVideoLlm,
            }),
            ...(options.useOpenAi !== undefined && {
                useOpenAi: options.useOpenAi,
            }),
        }
        : null;

    const response = await apiClient.post(
        `/api/analysis/${jobId}/retry`,
        requestBody,
        { timeout: ANALYSIS_COMMAND_TIMEOUT_MS }
    );

    return unwrapApiResponse(response);
}

export async function requestVideoLlmReanalysis(
    sourceJobId,
    { useOpenAi = true, idempotencyKey } = {}
) {
    const response = await apiClient.post(
        `/api/analysis/${sourceJobId}/video-llm-reanalysis`,
        { useOpenAi },
        {
            headers: {
                "Idempotency-Key": idempotencyKey,
            },
            timeout: ANALYSIS_COMMAND_TIMEOUT_MS,
        }
    );

    return unwrapApiResponse(response);
}

export async function cancelAnalysis(jobId) {
    const response = await apiClient.post(
        `/api/analysis/${jobId}/cancel`,
        null,
        { timeout: ANALYSIS_COMMAND_TIMEOUT_MS }
    );
    return unwrapApiResponse(response);
}

export async function getAnalysisStatus(jobId) {
    const response = await apiClient.get(
        `/api/analysis/${jobId}/status`,
        { timeout: ANALYSIS_POLL_TIMEOUT_MS }
    );
    return unwrapApiResponse(response);
}

// 분석이 진행되는 동안 몇 %쯤 진행됐는지 확인합니다. (Redis 기반 진행률 캐시)
export async function getAnalysisProgress(jobId) {
    const response = await apiClient.get(
        `/api/analysis/${jobId}/progress`,
        { timeout: ANALYSIS_POLL_TIMEOUT_MS }
    );
    return unwrapApiResponse(response);
}

export async function getResults({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get("/api/results", { params });
    return unwrapApiResponse(response);
}

export async function getResult(jobId) {
    const response = await apiClient.get(`/api/results/${jobId}`);
    return validateResultSchemaResponse(unwrapApiResponse(response));
}

export async function updateResultMemo(jobId, memo) {
    const response = await apiClient.patch(`/api/results/${jobId}/memo`, { memo });
    return unwrapApiResponse(response);
}

export async function getVideoAccessToken(jobId) {
    const response = await apiClient.post(
        `/api/results/${jobId}/video-access-token`
    );
    return unwrapApiResponse(response);
}

export async function deleteResult(jobId) {
    const response = await apiClient.delete(`/api/results/${jobId}`);
    return unwrapApiResponse(response);
}
