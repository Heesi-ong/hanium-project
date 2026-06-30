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

export async function getAnalysisStatus(jobId) {
    const response = await apiClient.get(`/api/analysis/${jobId}/status`);
    return unwrapApiResponse(response);
}

export async function getResults() {
    const response = await apiClient.get("/api/results");
    return unwrapApiResponse(response);
}

export async function getResult(jobId) {
    const response = await apiClient.get(`/api/results/${jobId}`);
    return unwrapApiResponse(response);
}

export async function deleteResult(jobId) {
    const response = await apiClient.delete(`/api/results/${jobId}`);
    return unwrapApiResponse(response);
}