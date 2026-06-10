import { apiRequest } from "./apiClient";

export const getModels = () => apiRequest("/api/models");
export const getConversations = (limit = 10, offset = 0, archived = false) =>
  apiRequest(`/api/conversations?limit=${limit}&offset=${offset}&archived=${archived}`);
export const getMessages = (conversationId, limit = 50, offset = 0) =>
  apiRequest(`/api/conversations/${conversationId}/messages?limit=${limit}&offset=${offset}`);
export const getUsageSummary = () => apiRequest("/api/usage/summary");

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
