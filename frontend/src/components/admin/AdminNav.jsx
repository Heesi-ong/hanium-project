import { NavLink } from "react-router-dom";

const LINKS = [
    { to: "/admin", label: "업무 개요", icon: "overview", end: true },
    { to: "/admin/users", label: "사용자 관리", icon: "users" },
    { to: "/admin/recovery", label: "복구 작업", icon: "recovery" },
    { to: "/admin/audit-logs", label: "감사로그", icon: "audit" },
];

function adminNavLinkClassName({ isActive }) {
    return `admin-nav-link${isActive ? " is-active" : ""}`;
}

function AdminNav() {
    return (
        <nav
            aria-label="관리자 메뉴"
            className="admin-nav"
        >
            <div className="admin-nav-context" aria-hidden="true">
                <span>Admin workspace</span>
                <strong>운영 도구</strong>
            </div>
            <div className="admin-nav-links">
                {LINKS.map((link) => (
                    <NavLink
                        key={link.to}
                        to={link.to}
                        end={link.end}
                        className={adminNavLinkClassName}
                    >
                        <AdminNavIcon name={link.icon} />
                        <span>{link.label}</span>
                    </NavLink>
                ))}
            </div>
        </nav>
    );
}

function AdminNavIcon({ name }) {
    const paths = {
        overview: "M4 4h7v7H4V4Zm9 0h7v4h-7V4ZM4 13h7v7H4v-7Zm9-3h7v10h-7V10Z",
        users: "M9.5 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm6.25-1a2.75 2.75 0 1 0 0-5.5 2.75 2.75 0 0 0 0 5.5ZM3 19.25C3 15.8 5.91 13 9.5 13s6.5 2.8 6.5 6.25V20H3v-.75Zm13.37-6.09A6.95 6.95 0 0 1 18 17.7V20h3v-.6c0-3.01-1.98-5.55-4.63-6.24Z",
        recovery: "M12 3a9 9 0 0 0-8.52 6H1l3.2 3.2L7.4 9H5.6A7 7 0 1 1 6 16.45l-1.43 1.4A9 9 0 1 0 12 3Zm-1 5v5l4.25 2.52.75-1.23-3.5-2.04V8H11Z",
        audit: "M7 3h10v2h3v16H4V5h3V3Zm2 2h6V4H9v1Zm-2 2v12h10V7H7Zm2 2h6v2H9V9Zm0 4h6v2H9v-2Z",
    };

    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d={paths[name]} />
        </svg>
    );
}

export default AdminNav;
