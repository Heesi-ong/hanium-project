import axios from "axios";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

const apiClient = axios.create({
    baseURL: API_BASE_URL,
    timeout: 300000,
    withCredentials: true,
});

apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response?.status === 401 && !error.config?.skipAuthRedirect) {
            if (!["/login", "/signup"].includes(window.location.pathname)) {
                sessionStorage.setItem("redirectAfterLogin", window.location.pathname);
                sessionStorage.setItem("sessionExpired", "true");
                window.location.assign("/login");
            }
        }

        const responseData = error.response?.data;

        if (responseData) {
            return Promise.reject(responseData);
        }

        return Promise.reject({
            success: false,
            status: 500,
            error: "NETWORK_ERROR",
            message: error.message || "서버와 통신할 수 없습니다.",
            timestamp: new Date().toISOString(),
        });
    }
);

export function unwrapApiResponse(response) {
    return response.data;
}

export function unwrapApiData(response) {
    return response.data?.data;
}

export default apiClient;
