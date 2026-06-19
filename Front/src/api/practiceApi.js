// 발표 목적, 연습 시리즈, 규칙 기반 코칭, AI 코칭 API 호출을 제공한다.
import { apiRequest } from "./apiClient";

export const savePracticeContext = (resultId, context) =>
  apiRequest(`/analyze/practice/${resultId}`, {
    method: "PUT",
    body: JSON.stringify(context),
  });

export const getPracticeCoaching = (resultId, signal) =>
  apiRequest(`/analyze/practice/${resultId}`, { signal });

export const getPracticeGrowth = (signal) => apiRequest("/analyze/practice/growth/all", { signal });

export const getPracticePurposes = (signal) => apiRequest("/analyze/practice/purposes", { signal });

export const getPracticeSeries = (signal) => apiRequest("/analyze/practice/series", { signal });

export const getAiCoaching = (resultId, signal) =>
  apiRequest(`/analyze/practice/${resultId}/ai-coaching`, { signal });

export const createAiCoaching = (resultId, signal) =>
  apiRequest(`/analyze/practice/${resultId}/ai-coaching`, { method: "POST", signal });

export const regenerateAiCoaching = (resultId, signal) =>
  apiRequest(`/analyze/practice/${resultId}/ai-coaching/regenerate`, {
    method: "POST",
    signal,
  });
