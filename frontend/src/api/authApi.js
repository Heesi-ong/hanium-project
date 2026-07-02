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
