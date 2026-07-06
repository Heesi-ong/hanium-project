import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { login as loginRequest, logout as logoutRequest } from "../api/authApi";
import { useToast } from "./ToastContext";

const AUTH_STORAGE_KEY = "presentationCoachAuth";

const AuthContext = createContext(null);

function readStoredAuth() {
    try {
        const storedValue = localStorage.getItem(AUTH_STORAGE_KEY);
        if (!storedValue) {
            return {
                token: "",
                user: null,
            };
        }

        const parsedValue = JSON.parse(storedValue);
        return {
            token: parsedValue.accessToken || "",
            user: parsedValue.user || null,
        };
    } catch {
        localStorage.removeItem(AUTH_STORAGE_KEY);
        return {
            token: "",
            user: null,
        };
    }
}

function saveAuth({ accessToken, user }) {
    localStorage.setItem(
        AUTH_STORAGE_KEY,
        JSON.stringify({
            accessToken,
            user,
        })
    );
}

function clearStoredAuth() {
    localStorage.removeItem(AUTH_STORAGE_KEY);
}

export function AuthProvider({ children }) {
    const [authState, setAuthState] = useState(readStoredAuth);
    const { showToast } = useToast();

    const login = useCallback(async (credentials) => {
        const response = await loginRequest(credentials);
        const responseData = response.data;

        const nextAuthState = {
            token: responseData.accessToken,
            user: responseData.user,
        };

        saveAuth({
            accessToken: responseData.accessToken,
            user: responseData.user,
        });
        setAuthState(nextAuthState);

        return responseData;
    }, []);

    const logout = useCallback(async ({ silent = false } = {}) => {
        try {
            await logoutRequest();
        } catch {
            // 서버 무효화 호출이 실패해도(네트워크 오류 등) 로컬 로그아웃은 항상 진행합니다.
        } finally {
            clearStoredAuth();
            setAuthState({
                token: "",
                user: null,
            });

            if (!silent) {
                showToast("로그아웃되었습니다.", "success");
            }
        }
    }, [showToast]);

    const value = useMemo(
        () => ({
            token: authState.token,
            user: authState.user,
            isAuthenticated: Boolean(authState.token),
            login,
            logout,
        }),
        [authState, login, logout]
    );

    return (
        <AuthContext.Provider value={value}>
            {children}
        </AuthContext.Provider>
    );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error("useAuth must be used within AuthProvider");
    }

    return context;
}
