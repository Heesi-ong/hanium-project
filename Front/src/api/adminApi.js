import { apiRequest } from "./apiClient";

export const getAdminStatus = (signal) => apiRequest("/api/admin/status", { signal });
export const getAdminProblemJobs = (signal) => apiRequest("/api/admin/analysis-jobs", { signal });
export const retryAdminProblemJob = (jobId) =>
  apiRequest(`/api/admin/analysis-jobs/${jobId}/retry`, { method: "POST" });
