import apiClient, { unwrapApiResponse } from "./apiClient";

export async function healthCheck() {
    const response = await apiClient.get("/api/health");
    return unwrapApiResponse(response);
}

export async function engineHealthCheck() {
    const response = await apiClient.get("/api/health/engines");
    return unwrapApiResponse(response);
}

export async function uploadAnalysisVideo(file) {
    const formData = new FormData();
    formData.append("file", file);

    const response = await apiClient.post("/api/analysis/upload", formData, {
        headers: {
            "Content-Type": "multipart/form-data",
        },
        timeout: 20 * 60 * 1000,
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
        requestBody
    );

    return unwrapApiResponse(response);
}

export async function retryAnalysis(jobId, options = {}) {
    const requestBody = {
        useVideoLlm: options.useVideoLlm ?? true,
        useOpenAi: options.useOpenAi ?? true,
    };

    const response = await apiClient.post(
        `/api/analysis/${jobId}/retry`,
        requestBody
    );

    return unwrapApiResponse(response);
}

export async function cancelAnalysis(jobId) {
    const response = await apiClient.post(`/api/analysis/${jobId}/cancel`);
    return unwrapApiResponse(response);
}

export async function getAnalysisStatus(jobId) {
    const response = await apiClient.get(`/api/analysis/${jobId}/status`);
    return unwrapApiResponse(response);
}

// 분석이 진행되는 동안 몇 %쯤 진행됐는지 확인합니다. (Redis 기반 진행률 캐시)
export async function getAnalysisProgress(jobId) {
    const response = await apiClient.get(`/api/analysis/${jobId}/progress`);
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
