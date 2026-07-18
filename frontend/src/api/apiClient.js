import axios from "axios";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
export const DEFAULT_API_TIMEOUT_MS = 15 * 1000;

const apiClient = axios.create({
    baseURL: API_BASE_URL,
    timeout: DEFAULT_API_TIMEOUT_MS,
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

        const isTimeout = error.code === "ECONNABORTED";

        return Promise.reject({
            success: false,
            status: 500,
            error: isTimeout ? "REQUEST_TIMEOUT" : "NETWORK_ERROR",
            message: isTimeout
                ? "요청 시간이 초과되었습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요."
                : "서버와 통신할 수 없습니다. 네트워크 연결을 확인해주세요.",
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
