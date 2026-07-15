import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
    fetchCurrentUser,
    login as loginRequest,
    logout as logoutRequest,
} from "../api/authApi";
import { useToast } from "./ToastContext";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
    const [user, setUser] = useState(null);
    const [isInitializing, setIsInitializing] = useState(true);
    const { showToast } = useToast();

    useEffect(() => {
        let isMounted = true;

        async function restoreSession() {
            try {
                const response = await fetchCurrentUser();
                if (isMounted) {
                    setUser(response.data);
                }
            } catch {
                if (isMounted) {
                    setUser(null);
                }
            } finally {
                if (isMounted) {
                    setIsInitializing(false);
                }
            }
        }

        restoreSession();

        return () => {
            isMounted = false;
        };
    }, []);

    const login = useCallback(async (credentials) => {
        const response = await loginRequest(credentials);
        const responseData = response.data;

        setUser(responseData.user);

        return responseData;
    }, []);

    const logout = useCallback(async ({ silent = false } = {}) => {
        try {
            await logoutRequest();
        } catch {
            // 서버 무효화 호출이 실패해도(네트워크 오류 등) 로컬 로그아웃은 항상 진행합니다.
        } finally {
            setUser(null);

            if (!silent) {
                showToast("로그아웃되었습니다.", "success");
            }
        }
    }, [showToast]);

    const updateUser = useCallback((patch) => {
        setUser((previous) => (previous ? { ...previous, ...patch } : previous));
    }, []);

    const value = useMemo(
        () => ({
            user,
            isInitializing,
            isAuthenticated: Boolean(user),
            login,
            logout,
            updateUser,
        }),
        [user, isInitializing, login, logout, updateUser]
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
