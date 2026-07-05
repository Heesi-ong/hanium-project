import apiClient, { unwrapApiResponse } from "./apiClient";

export async function signup({ email, password }) {
    const response = await apiClient.post("/api/auth/signup", {
        email,
        password,
    });

    return unwrapApiResponse(response);
}

export async function login({ email, password }) {
    const response = await apiClient.post("/api/auth/login", {
        email,
        password,
    });

    return unwrapApiResponse(response);
}

export async function withdrawAccount(password) {
    const response = await apiClient.delete("/api/users/me", {
        data: { password },
    });

    return unwrapApiResponse(response);
}

export async function logout() {
    const response = await apiClient.post("/api/auth/logout");

    return unwrapApiResponse(response);
}
