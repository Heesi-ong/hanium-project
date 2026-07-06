import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import MainLayout from "../layouts/MainLayout";
import ProtectedRoute from "./ProtectedRoute";

const HomePage = lazy(() => import("../pages/HomePage"));
const UploadPage = lazy(() => import("../pages/UploadPage"));
const ResultListPage = lazy(() => import("../pages/ResultListPage"));
const ResultDetailPage = lazy(() => import("../pages/ResultDetailPage"));
const LoginPage = lazy(() => import("../pages/LoginPage"));
const SignupPage = lazy(() => import("../pages/SignupPage"));
const AccountPage = lazy(() => import("../pages/AccountPage"));
const StatusPage = lazy(() => import("../pages/StatusPage"));
const NotFoundPage = lazy(() => import("../pages/NotFoundPage"));

function RouteLoadingFallback() {
    return (
        <div style={{ padding: "48px 24px", textAlign: "center" }}>
            불러오는 중...
        </div>
    );
}

function AppRoutes() {
    return (
        <Suspense fallback={<RouteLoadingFallback />}>
            <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/signup" element={<SignupPage />} />

                <Route element={<ProtectedRoute />}>
                    <Route element={<MainLayout />}>
                        <Route path="/" element={<HomePage />} />
                        <Route path="/upload" element={<UploadPage />} />
                        <Route path="/results" element={<ResultListPage />} />
                        <Route path="/results/:jobId" element={<ResultDetailPage />} />
                        <Route path="/account" element={<AccountPage />} />
                        <Route path="/status" element={<StatusPage />} />
                        <Route path="*" element={<NotFoundPage />} />
                    </Route>
                </Route>
            </Routes>
        </Suspense>
    );
}

export default AppRoutes;
