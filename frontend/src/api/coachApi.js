import apiClient, { unwrapApiResponse } from "./apiClient";

export async function getCoachMessages(jobId) {
    const response = await apiClient.get(`/api/results/${jobId}/coach/messages`);
    return unwrapApiResponse(response);
}

export async function sendCoachMessage(jobId, content) {
    const response = await apiClient.post(`/api/results/${jobId}/coach/messages`, {
        content,
    });
    return unwrapApiResponse(response);
}
