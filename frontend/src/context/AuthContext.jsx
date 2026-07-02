import { createContext, useContext, useMemo, useState } from "react";
import { login as loginRequest } from "../api/authApi";

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

    async function login(credentials) {
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
    }

    function logout() {
        clearStoredAuth();
        setAuthState({
            token: "",
            user: null,
        });
    }

    const value = useMemo(
        () => ({
            token: authState.token,
            user: authState.user,
            isAuthenticated: Boolean(authState.token),
            login,
            logout,
        }),
        [authState]
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
