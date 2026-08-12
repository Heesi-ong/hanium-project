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

// 정지/강제 탈퇴/결과 삭제/수동 재큐잉은 파괴적 조치라 실행 사유(reason)가 필수이고,
// 인시던트/문의 티켓 번호(incidentId)는 선택적으로 함께 남길 수 있다(P2-03).
export async function suspendAdminUser(userId, { reason, incidentId } = {}) {
    const response = await apiClient.post(`/api/admin/users/${userId}/suspend`, {
        reason,
        incidentId,
    });
    return unwrapApiResponse(response);
}

export async function activateAdminUser(userId) {
    const response = await apiClient.post(`/api/admin/users/${userId}/activate`);
    return unwrapApiResponse(response);
}

export async function forceWithdrawAdminUser(userId, { reason, incidentId } = {}) {
    const response = await apiClient.post(`/api/admin/users/${userId}/withdraw`, {
        reason,
        incidentId,
    });
    return unwrapApiResponse(response);
}

export async function deleteAdminResult(jobId, { reason, incidentId } = {}) {
    const response = await apiClient.delete(`/api/admin/results/${jobId}`, {
        data: { reason, incidentId },
    });
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

export async function requeueAdminDeadLetterJob(jobId, { reason, incidentId } = {}) {
    const response = await apiClient.post(`/api/admin/analysis-jobs/${jobId}/requeue`, {
        reason,
        incidentId,
    });
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

export async function requeueAdminStorageDeletionDeadLetter(taskId, { reason, incidentId } = {}) {
    const response = await apiClient.post(
        `/api/admin/storage-deletion-tasks/${taskId}/requeue`,
        { reason, incidentId }
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

export async function requeueAdminPasswordResetEmailDeadLetter(taskId, { reason, incidentId } = {}) {
    const response = await apiClient.post(
        `/api/admin/password-reset-email-tasks/${taskId}/requeue`,
        { reason, incidentId }
    );
    return unwrapApiResponse(response);
}
