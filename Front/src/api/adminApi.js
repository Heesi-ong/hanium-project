// 관리자 집계, 사용자 관리, 시스템 상태, 문제 작업 재시도 API 호출을 제공한다.
import { apiRequest } from "./apiClient";

export const getAdminStatus = (signal) => apiRequest("/api/admin/status", { signal });
export const getAdminMetrics = (signal) => apiRequest("/api/admin/metrics", { signal });
export const getAdminUsers = (params = {}, signal) => {
  const query = new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== "" && value != null),
  );
  return apiRequest(`/api/admin/users?${query}`, { signal });
};
export const updateAdminUserStatus = (userId, status) =>
  apiRequest(`/api/admin/users/${userId}/status?status=${encodeURIComponent(status)}`, {
    method: "PATCH",
  });
export const getAdminProblemJobs = (signal) => apiRequest("/api/admin/analysis-jobs", { signal });
export const retryAdminProblemJob = (jobId) =>
  apiRequest(`/api/admin/analysis-jobs/${jobId}/retry`, { method: "POST" });
