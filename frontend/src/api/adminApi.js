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
