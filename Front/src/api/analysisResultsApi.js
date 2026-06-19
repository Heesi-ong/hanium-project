// 분석 결과 목록, 상세, 성장 추이, Markdown 보고서 다운로드 API 호출을 제공한다.
import { apiBlobRequest, apiRequest } from "./apiClient";

export function getAnalyzeResults(params = {}, signal) {
  const query = new URLSearchParams(
    Object.entries(params).filter(
      ([, value]) => value !== "" && value !== null && value !== undefined,
    ),
  );
  return apiRequest(`/analyze/results?${query}`, { signal });
}

export const getAnalyzeSummary = (resultId, signal) =>
  apiRequest(`/analyze/result/${resultId}/summary`, { signal });

export const getAnalyzeSections = (resultId, signal) =>
  apiRequest(`/analyze/result/${resultId}/sections`, { signal });

export const getTimelineChart = (resultId, signal) =>
  apiRequest(`/analyze/result/${resultId}/timeline/chart`, { signal });

export const deleteAnalyzeResult = (resultId) =>
  apiRequest(`/analyze/result/${resultId}`, { method: "DELETE" });

export const getAnalyzeGrowth = (signal) => apiRequest("/analyze/growth", { signal });

export const downloadAnalyzeReport = (resultId, signal) =>
  apiBlobRequest(`/analyze/result/${resultId}/report.md`, { signal });
