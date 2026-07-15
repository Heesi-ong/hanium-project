import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

function AdminRoute() {
    const location = useLocation();
    const { isAuthenticated, isInitializing, user } = useAuth();

    if (isInitializing) {
        return null;
    }

    if (!isAuthenticated) {
        return <Navigate to="/login" replace state={{ from: location }} />;
    }

    if (!user?.admin) {
        return <Navigate to="/" replace />;
    }

    return <Outlet />;
}

export default AdminRoute;
