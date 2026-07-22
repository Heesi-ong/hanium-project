import apiClient, { unwrapApiResponse } from "./apiClient";

export async function signup({ email, password, agreedToTerms }) {
    const response = await apiClient.post("/api/auth/signup", {
        email,
        password,
        agreedToTerms,
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

export async function fetchCurrentUser() {
    const response = await apiClient.get("/api/auth/me", {
        skipAuthRedirect: true,
    });

    return unwrapApiResponse(response);
}

export async function withdrawAccount(password) {
    const response = await apiClient.delete("/api/users/me", {
        data: { password },
    });

    return unwrapApiResponse(response);
}

export async function changePassword({ currentPassword, newPassword }) {
    const response = await apiClient.post("/api/users/me/password", {
        currentPassword,
        newPassword,
    });

    return unwrapApiResponse(response);
}

export async function requestPasswordReset(email) {
    const response = await apiClient.post("/api/auth/password-reset/request", {
        email,
    });

    return unwrapApiResponse(response);
}

export async function confirmPasswordReset({ token, newPassword }) {
    const response = await apiClient.post("/api/auth/password-reset/confirm", {
        token,
        newPassword,
    });

    return unwrapApiResponse(response);
}

export async function logout() {
    const response = await apiClient.post("/api/auth/logout");

    return unwrapApiResponse(response);
}
