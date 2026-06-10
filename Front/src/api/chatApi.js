import { apiRequest } from "./apiClient";

export const getModels = (signal) => apiRequest("/api/models", { signal });
export const getConversations = (limit = 10, offset = 0, archived = false, cursor = "", signal) => {
  const params = new URLSearchParams({ limit, offset, archived });
  if (cursor) params.set("cursor", cursor);
  return apiRequest(`/api/conversations?${params}`, { signal });
};
export const getMessages = (conversationId, limit = 50, offset = 0, cursor = "", signal) => {
  const params = new URLSearchParams({ limit, offset });
  if (cursor) params.set("cursor", cursor);
  return apiRequest(`/api/conversations/${conversationId}/messages?${params}`, { signal });
};
export const getUsageSummary = (signal) => apiRequest("/api/usage/summary", { signal });

export const createConversation = (payload = {}) =>
  apiRequest("/api/conversations", {
    method: "POST",
    body: JSON.stringify(payload),
  });

export const sendChat = (conversationId, content, signal) =>
  apiRequest(`/api/conversations/${conversationId}/chat`, {
    method: "POST",
    body: JSON.stringify({ content }),
    headers: { "Idempotency-Key": crypto.randomUUID() },
    signal,
  });

export const renameConversation = (conversationId, title) =>
  apiRequest(`/api/conversations/${conversationId}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });

export const archiveConversation = (conversationId) =>
  apiRequest(`/api/conversations/${conversationId}/archive`, { method: "POST" });
export const restoreConversation = (conversationId) =>
  apiRequest(`/api/conversations/${conversationId}/restore`, { method: "POST" });

export const deleteConversation = (conversationId) =>
  apiRequest(`/api/conversations/${conversationId}`, { method: "DELETE" });
