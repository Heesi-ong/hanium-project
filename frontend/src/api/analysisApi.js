import apiClient from "./apiClient";

export async function healthCheck() {
    const response = await apiClient.get("/api/health");
    return response.data;
}

export async function engineHealthCheck() {
    const response = await apiClient.get("/api/health/engines");
    return response.data;
}

export async function uploadAnalysisVideo(file) {
    const formData = new FormData();
    formData.append("file", file);

    const response = await apiClient.post("/api/analysis/upload", formData, {
        headers: {
            "Content-Type": "multipart/form-data",
        },
    });

    return response.data;
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

    return response.data;
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

    return response.data;
}

export async function getAnalysisStatus(jobId) {
    const response = await apiClient.get(`/api/analysis/${jobId}/status`);
    return response.data;
}

export async function getResults() {
    const response = await apiClient.get("/api/results");
    return response.data;
}

export async function getResult(jobId) {
    const response = await apiClient.get(`/api/results/${jobId}`);
    return response.data;
}

export async function deleteResult(jobId) {
    const response = await apiClient.delete(`/api/results/${jobId}`);
    return response.data;
}