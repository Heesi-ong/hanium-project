import { apiRequest } from "./apiClient";

export const getMe = () => apiRequest("/api/auth/me");
export const getStorageUsage = () => apiRequest("/api/auth/storage");
export const exportUserData = () => apiRequest("/api/auth/export");

export const register = (payload) =>
  apiRequest("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });

export const login = (payload) =>
  apiRequest("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(payload),
  });

export const logout = () => apiRequest("/api/auth/logout", { method: "POST" });

export const updateProfile = (payload) =>
  apiRequest("/api/auth/profile", {
    method: "PUT",
    body: JSON.stringify(payload),
  });

export const changePassword = (payload) =>
  apiRequest("/api/auth/password", {
    method: "PUT",
    body: JSON.stringify(payload),
  });

export const logoutAll = () => apiRequest("/api/auth/logout-all", { method: "POST" });

export const deleteAccount = (payload) =>
  apiRequest("/api/auth/account", {
    method: "DELETE",
    body: JSON.stringify(payload),
  });
