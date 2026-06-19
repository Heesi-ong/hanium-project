// 분석 작업 상태 조회, 취소, 재시도 API 호출을 제공한다.
import { apiRequest } from "./apiClient";

export const getAnalyzeJob = (resultId, signal) =>
  apiRequest(`/analyze/job/${resultId}`, { signal });

export const cancelAnalyzeJob = (resultId) =>
  apiRequest(`/analyze/job/${resultId}/cancel`, { method: "POST" });

export const retryAnalyzeJob = (resultId) =>
  apiRequest(`/analyze/job/${resultId}/retry`, { method: "POST" });
