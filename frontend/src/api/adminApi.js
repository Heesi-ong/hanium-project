import apiClient, { unwrapApiResponse } from "./apiClient";

export async function getAdminUsers({ page, size, email, status, role } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    if (email) {
        params.email = email;
    }

    if (status) {
        params.status = status;
    }

    if (role) {
        params.role = role;
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

export async function getAdminAuditLogs({
    page,
    size,
    adminEmail,
    action,
    targetType,
    targetId,
    from,
    to,
} = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    if (adminEmail) {
        params.adminEmail = adminEmail;
    }

    if (action) {
        params.action = action;
    }

    if (targetType) {
        params.targetType = targetType;
    }

    if (targetId) {
        params.targetId = targetId;
    }

    if (from) {
        params.from = from;
    }

    if (to) {
        params.to = to;
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

export async function getAdminStorageDeletionDeadLetters({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get(
        "/api/admin/storage-deletion-tasks/dead-letter",
        { params }
    );
    return unwrapApiResponse(response);
}

export async function requeueAdminStorageDeletionDeadLetter(taskId) {
    const response = await apiClient.post(
        `/api/admin/storage-deletion-tasks/${taskId}/requeue`
    );
    return unwrapApiResponse(response);
}

export async function getAdminPasswordResetEmailDeadLetters({ page, size } = {}) {
    const params = {};

    if (page !== undefined) {
        params.page = page;
    }

    if (size !== undefined) {
        params.size = size;
    }

    const response = await apiClient.get(
        "/api/admin/password-reset-email-tasks/dead-letter",
        { params }
    );
    return unwrapApiResponse(response);
}

export async function requeueAdminPasswordResetEmailDeadLetter(taskId) {
    const response = await apiClient.post(
        `/api/admin/password-reset-email-tasks/${taskId}/requeue`
    );
    return unwrapApiResponse(response);
}
