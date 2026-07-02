import { Route, Routes } from "react-router-dom";
import MainLayout from "../layouts/MainLayout";
import HomePage from "../pages/HomePage";
import UploadPage from "../pages/UploadPage";
import ResultListPage from "../pages/ResultListPage";
import ResultDetailPage from "../pages/ResultDetailPage";
import LoginPage from "../pages/LoginPage";
import SignupPage from "../pages/SignupPage";
import ProtectedRoute from "./ProtectedRoute";

function AppRoutes() {
    return (
        <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />

            <Route element={<ProtectedRoute />}>
                <Route element={<MainLayout />}>
                    <Route path="/" element={<HomePage />} />
                    <Route path="/upload" element={<UploadPage />} />
                    <Route path="/results" element={<ResultListPage />} />
                    <Route path="/results/:jobId" element={<ResultDetailPage />} />
                </Route>
            </Route>
        </Routes>
    );
}

export default AppRoutes;
