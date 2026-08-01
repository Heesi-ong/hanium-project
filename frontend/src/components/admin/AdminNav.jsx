import { NavLink } from "react-router-dom";

const LINKS = [
    { to: "/admin", label: "업무 개요", end: true },
    { to: "/admin/users", label: "사용자 관리" },
    { to: "/admin/recovery", label: "복구 작업" },
    { to: "/admin/audit-logs", label: "감사로그" },
];

function adminNavLinkClassName({ isActive }) {
    const base = "rounded-lg px-4 py-2 text-sm font-bold transition-colors";
    return isActive
        ? `${base} bg-primary-deep text-warm-white`
        : `${base} bg-surface-primary text-text-secondary hover:text-text-primary`;
}

function AdminNav() {
    return (
        <nav
            aria-label="관리자 메뉴"
            className="mb-7 flex flex-wrap gap-2 rounded-2xl border border-white/10 bg-surface-primary/60 p-2"
        >
            {LINKS.map((link) => (
                <NavLink
                    key={link.to}
                    to={link.to}
                    end={link.end}
                    className={adminNavLinkClassName}
                >
                    {link.label}
                </NavLink>
            ))}
        </nav>
    );
}

export default AdminNav;
