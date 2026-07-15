import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import MainLayout from "../layouts/MainLayout";
import AdminRoute from "./AdminRoute";
import ProtectedRoute from "./ProtectedRoute";
import Spinner from "../components/Spinner";

const HomePage = lazy(() => import("../pages/HomePage"));
const UploadPage = lazy(() => import("../pages/UploadPage"));
const ResultListPage = lazy(() => import("../pages/ResultListPage"));
const ResultDetailPage = lazy(() => import("../pages/ResultDetailPage"));
const LoginPage = lazy(() => import("../pages/LoginPage"));
const SignupPage = lazy(() => import("../pages/SignupPage"));
const ForgotPasswordPage = lazy(() => import("../pages/ForgotPasswordPage"));
const ResetPasswordPage = lazy(() => import("../pages/ResetPasswordPage"));
const OnboardingPage = lazy(() => import("../pages/OnboardingPage"));
const PrivacyPage = lazy(() => import("../pages/PrivacyPage"));
const TermsPage = lazy(() => import("../pages/TermsPage"));
const PricingPage = lazy(() => import("../pages/PricingPage"));
const AccountPage = lazy(() => import("../pages/AccountPage"));
const StatusPage = lazy(() => import("../pages/StatusPage"));
const AdminDashboardPage = lazy(() => import("../pages/AdminDashboardPage"));
const AdminUserDetailPage = lazy(() => import("../pages/AdminUserDetailPage"));
const NotFoundPage = lazy(() => import("../pages/NotFoundPage"));

function RouteLoadingFallback() {
    return (
        <div style={{ padding: "48px 24px", textAlign: "center" }}>
            <Spinner />
            <p style={{ marginTop: "12px" }}>불러오는 중...</p>
        </div>
    );
}

function AppRoutes() {
    return (
        <Suspense fallback={<RouteLoadingFallback />}>
            <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/signup" element={<SignupPage />} />
                <Route path="/forgot-password" element={<ForgotPasswordPage />} />
                <Route path="/reset-password" element={<ResetPasswordPage />} />

                <Route element={<MainLayout />}>
                    <Route path="/" element={<HomePage />} />
                    <Route path="/privacy" element={<PrivacyPage />} />
                    <Route path="/terms" element={<TermsPage />} />
                    <Route path="/pricing" element={<PricingPage />} />

                    <Route element={<ProtectedRoute />}>
                        <Route path="/onboarding" element={<OnboardingPage />} />
                        <Route path="/upload" element={<UploadPage />} />
                        <Route path="/results" element={<ResultListPage />} />
                        <Route path="/results/:jobId" element={<ResultDetailPage />} />
                        <Route path="/account" element={<AccountPage />} />
                        <Route path="/status" element={<StatusPage />} />
                    </Route>

                    <Route element={<AdminRoute />}>
                        <Route path="/admin" element={<AdminDashboardPage />} />
                        <Route path="/admin/users/:userId" element={<AdminUserDetailPage />} />
                    </Route>

                    <Route path="*" element={<NotFoundPage />} />
                </Route>
            </Routes>
        </Suspense>
    );
}

export default AppRoutes;
