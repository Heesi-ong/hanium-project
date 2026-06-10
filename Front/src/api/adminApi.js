import { apiRequest } from "./apiClient";

export const getAdminStatus = (signal) => apiRequest("/api/admin/status", { signal });
