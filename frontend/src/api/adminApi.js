import apiClient, { unwrapApiResponse } from "./apiClient";

export async function getAdminUsers({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get("/api/admin/users", { params });
    return unwrapApiResponse(response);
}

export async function getAdminStats() {
    const response = await apiClient.get("/api/admin/stats");
    return unwrapApiResponse(response);
}

export async function getAdminUserResults(userId, { page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get(`/api/admin/users/${userId}/results`, { params });
    return unwrapApiResponse(response);
}

export async function suspendAdminUser(userId) {
    const response = await apiClient.post(`/api/admin/users/${userId}/suspend`);
    return unwrapApiResponse(response);
}

export async function activateAdminUser(userId) {
    const response = await apiClient.post(`/api/admin/users/${userId}/activate`);
    return unwrapApiResponse(response);
}

export async function forceWithdrawAdminUser(userId) {
    const response = await apiClient.post(`/api/admin/users/${userId}/withdraw`);
    return unwrapApiResponse(response);
}

export async function deleteAdminResult(jobId) {
    const response = await apiClient.delete(`/api/admin/results/${jobId}`);
    return unwrapApiResponse(response);
}

export async function getAdminAuditLogs({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get("/api/admin/audit-logs", { params });
    return unwrapApiResponse(response);
}

export async function getAdminDeadLetterJobs({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get("/api/admin/analysis-jobs/dead-letter", { params });
    return unwrapApiResponse(response);
}

export async function requeueAdminDeadLetterJob(jobId) {
    const response = await apiClient.post(`/api/admin/analysis-jobs/${jobId}/requeue`);
    return unwrapApiResponse(response);
}
