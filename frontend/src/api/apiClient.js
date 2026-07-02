import axios from "axios";

const API_BASE_URL =
    import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
const AUTH_STORAGE_KEY = "presentationCoachAuth";

const apiClient = axios.create({
    baseURL: API_BASE_URL,
    timeout: 300000,
});

function getStoredAccessToken() {
    try {
        const storedValue = localStorage.getItem(AUTH_STORAGE_KEY);
        if (!storedValue) {
            return "";
        }

        return JSON.parse(storedValue).accessToken || "";
    } catch {
        localStorage.removeItem(AUTH_STORAGE_KEY);
        return "";
    }
}

function clearStoredAccessToken() {
    localStorage.removeItem(AUTH_STORAGE_KEY);
}

apiClient.interceptors.request.use((config) => {
    const accessToken = getStoredAccessToken();

    if (accessToken) {
        config.headers = config.headers || {};
        config.headers.Authorization = `Bearer ${accessToken}`;
    }

    return config;
});

apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response?.status === 401) {
            clearStoredAccessToken();

            if (!["/login", "/signup"].includes(window.location.pathname)) {
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
