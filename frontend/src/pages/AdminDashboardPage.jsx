import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import {
    getAdminDeadLetterJobs,
    getAdminPasswordResetEmailDeadLetters,
    getAdminStats,
    getAdminStorageDeletionDeadLetters,
} from "../api/adminApi";
import { getErrorMessage } from "../api/errorUtils";
import AdminNav from "../components/admin/AdminNav";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { EASE_OUT } from "../components/motion/animationVariants";

const EMPTY_COUNTS = {
    analysis: null,
    storageDeletion: null,
    passwordResetEmail: null,
};

function AdminDashboardPage() {
    const [stats, setStats] = useState(null);
    const [recoveryCounts, setRecoveryCounts] = useState(EMPTY_COUNTS);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const prefersReducedMotion = useReducedMotion();

    const loadOverview = useCallback(async () => {
        setLoading(true);
        setError("");

        const results = await Promise.allSettled([
            getAdminStats(),
            getAdminDeadLetterJobs({ page: 0, size: 1 }),
            getAdminStorageDeletionDeadLetters({ page: 0, size: 1 }),
            getAdminPasswordResetEmailDeadLetters({ page: 0, size: 1 }),
        ]);

        const [statsResult, analysisResult, storageResult, passwordResetResult] = results;
        const errors = [];

        if (statsResult.status === "fulfilled") {
            setStats(statsResult.value.data);
        } else {
            errors.push(getErrorMessage(statsResult.reason, "집계 통계를 불러오지 못했습니다."));
        }

        setRecoveryCounts({
            analysis: getTotalElements(analysisResult, errors, "분석 복구 건수를 불러오지 못했습니다."),
            storageDeletion: getTotalElements(storageResult, errors, "삭제 복구 건수를 불러오지 못했습니다."),
            passwordResetEmail: getTotalElements(passwordResetResult, errors, "이메일 복구 건수를 불러오지 못했습니다."),
        });
        setError([...new Set(errors)].join(" "));
        setLoading(false);
    }, []);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 관리자 개요의 서버 집계값을 최초 진입 시 불러옵니다.
        loadOverview();
    }, [loadOverview]);

    const motionProps = prefersReducedMotion
        ? { initial: false }
        : {
            initial: { opacity: 0, y: 14 },
            animate: { opacity: 1, y: 0 },
            transition: { duration: 0.42, ease: EASE_OUT },
        };

    const recoveryItems = [
        {
            label: "분석 복구",
            value: recoveryCounts.analysis,
            description: "재시도 횟수를 모두 사용한 분석 작업",
            icon: "analysis",
        },
        {
            label: "삭제 복구",
            value: recoveryCounts.storageDeletion,
            description: "스토리지에서 아직 정리되지 않은 파일",
            icon: "storage",
        },
        {
            label: "이메일 복구",
            value: recoveryCounts.passwordResetEmail,
            description: "발송되지 않은 비밀번호 재설정 메일",
            icon: "email",
        },
    ];
    const allRecoveryCountsKnown = recoveryItems.every(({ value }) => typeof value === "number");
    const recoveryTotal = allRecoveryCountsKnown
        ? recoveryItems.reduce((total, { value }) => total + value, 0)
        : null;

    return (
        <section className="page-section admin-dashboard-page">
            <DashboardHero loading={loading} onRefresh={loadOverview} />

            <AdminNav />

            {loading && !stats ? (
                <div className="admin-dashboard-loading" aria-live="polite">
                    <EmptyState
                        loading
                        title="운영 데이터를 불러오는 중입니다"
                        description="사용자 집계와 세 가지 복구 대기열을 차례로 확인하고 있습니다."
                    />
                </div>
            ) : (
                <>
                    <StateMessage type="error">{error}</StateMessage>

                    <motion.section className="admin-overview-section" aria-labelledby="admin-snapshot-title" {...motionProps}>
                        <SectionHeading
                            eyebrow="Data snapshot"
                            title="서비스 데이터 스냅샷"
                            id="admin-snapshot-title"
                            description="서버가 제공한 현재 누적 집계입니다."
                        />
                        <div className="admin-metric-grid">
                            <SummaryCard label="전체 가입자" value={stats?.totalUsers} description="등록된 사용자" icon="users" />
                            <SummaryCard label="관리자" value={stats?.adminUsers} description="관리 권한 계정" icon="admin" />
                            <SummaryCard label="전체 분석" value={stats?.totalAnalysisJobs} description="접수된 분석 작업" icon="analysis" />
                            <SummaryCard label="완료 분석" value={stats?.completedAnalysisJobs} description="정상 완료된 작업" icon="complete" tone="success" />
                        </div>
                    </motion.section>

                    <motion.section
                        className="admin-overview-section admin-recovery-section"
                        aria-labelledby="admin-recovery-title"
                        {...motionProps}
                        transition={prefersReducedMotion ? undefined : { duration: 0.42, delay: 0.08, ease: EASE_OUT }}
                    >
                        <SectionHeading
                            eyebrow="Recovery queue"
                            title="조치가 필요한 복구 대기열"
                            id="admin-recovery-title"
                            description="DEAD_LETTER로 분류된 항목을 유형별로 확인합니다."
                            badge={recoveryTotal === null ? "일부 집계 확인 필요" : `총 ${recoveryTotal}건`}
                            badgeTone={recoveryTotal > 0 || recoveryTotal === null ? "warning" : "success"}
                        />
                        <div className="admin-recovery-grid">
                            {recoveryItems.map((item) => (
                                <RecoveryCard key={item.label} {...item} />
                            ))}
                        </div>
                        <p className="admin-operational-note">
                            <AdminIcon name="info" />
                            <span>분석 작업의 실시간 진행 상태는 기존 로컬 로그 감시 스크립트에서 확인합니다.</span>
                        </p>
                    </motion.section>

                    <motion.section
                        className="admin-overview-section"
                        aria-labelledby="admin-actions-title"
                        {...motionProps}
                        transition={prefersReducedMotion ? undefined : { duration: 0.42, delay: 0.16, ease: EASE_OUT }}
                    >
                        <SectionHeading
                            eyebrow="Quick actions"
                            title="관리 작업 바로가기"
                            id="admin-actions-title"
                            description="역할에 따라 사용자, 복구 항목, 감사 기록을 검토합니다."
                        />
                        <div className="admin-action-grid">
                            <AdminActionCard
                                icon="users"
                                sequence="01"
                                title="사용자 관리"
                                description="사용자를 검색하고 계정 상태와 소유 분석 결과를 관리합니다."
                                to="/admin/users"
                                linkLabel="사용자 관리 열기"
                            />
                            <AdminActionCard
                                icon="recovery"
                                sequence="02"
                                title="복구 작업"
                                description="분석·스토리지 삭제·비밀번호 이메일 DEAD_LETTER를 검토하고 재큐잉합니다."
                                to="/admin/recovery"
                                linkLabel="복구 작업 열기"
                            />
                            <AdminActionCard
                                icon="audit"
                                sequence="03"
                                title="감사로그"
                                description="관리자 조치 이력과 대상, 처리 결과를 시간 순서로 확인합니다."
                                to="/admin/audit-logs"
                                linkLabel="감사로그 열기"
                            />
                        </div>
                    </motion.section>
                </>
            )}
        </section>
    );
}

function DashboardHero({ loading, onRefresh }) {
    return (
        <div className="admin-dashboard-hero">
            <div className="admin-dashboard-hero-copy">
                <span className="admin-role-badge">
                    <AdminIcon name="shield" />
                    관리자 전용 워크스페이스
                </span>
                <PageHeader
                    eyebrow="Admin command center"
                    title="관리자 업무 개요"
                    description="로컬 테스트 환경의 사용자·분석 현황을 살피고, 사람이 확인해야 하는 복구 작업으로 바로 이동합니다."
                />
            </div>
            <aside className="admin-dashboard-hero-panel" aria-label="현재 관리자 화면">
                <span>Current view</span>
                <strong>운영 개요</strong>
                <p>집계 시점의 서버 응답을 그대로 표시합니다.</p>
                <button type="button" className="secondary-button admin-refresh-button" onClick={onRefresh} disabled={loading}>
                    <AdminIcon name="refresh" />
                    {loading ? "새로고침 중..." : "개요 새로고침"}
                </button>
            </aside>
        </div>
    );
}

function getTotalElements(result, errors, fallbackMessage) {
    if (result.status === "fulfilled") {
        return result.value.data?.totalElements ?? 0;
    }

    errors.push(getErrorMessage(result.reason, fallbackMessage));
    return null;
}

function SectionHeading({ eyebrow, title, id, description, badge, badgeTone = "neutral" }) {
    return (
        <div className="admin-section-heading">
            <div>
                <span className="admin-section-eyebrow">{eyebrow}</span>
                <h2 id={id}>{title}</h2>
                <p>{description}</p>
            </div>
            {badge && <span className={`admin-count-badge is-${badgeTone}`}>{badge}</span>}
        </div>
    );
}

function SummaryCard({ label, value, description, icon, tone = "default" }) {
    const numericValue = typeof value === "number" ? value : null;
    return (
        <article className={`admin-metric-card is-${tone}`}>
            <div className="admin-card-icon"><AdminIcon name={icon} /></div>
            <div>
                <span>{label}</span>
                <strong>{numericValue ?? "-"}</strong>
                <p>{numericValue === null ? "집계 확인 필요" : description}</p>
            </div>
        </article>
    );
}

function RecoveryCard({ label, value, description, icon }) {
    const numericValue = typeof value === "number" ? value : null;
    const state = numericValue === null ? "unknown" : numericValue > 0 ? "attention" : "clear";
    const stateLabel = state === "unknown" ? "확인 필요" : state === "attention" ? "조치 대기" : "대기 없음";
    const accessibleValue = numericValue === null ? "집계 확인 필요" : `${numericValue}건`;

    return (
        <Link to="/admin/recovery" className={`admin-recovery-card is-${state}`} aria-label={`${label} ${accessibleValue}, 복구 작업 열기`}>
            <div className="admin-card-icon"><AdminIcon name={icon} /></div>
            <div className="admin-recovery-card-copy">
                <span>{label}</span>
                <strong>{numericValue ?? "-"}</strong>
                <p>{description}</p>
            </div>
            <span className="admin-recovery-state">{stateLabel}</span>
            <AdminIcon name="arrow" />
        </Link>
    );
}

function AdminActionCard({ icon, sequence, title, description, to, linkLabel }) {
    return (
        <article className="admin-action-card">
            <div className="admin-action-card-topline">
                <span>{sequence}</span>
                <div className="admin-card-icon"><AdminIcon name={icon} /></div>
            </div>
            <h3>{title}</h3>
            <p>{description}</p>
            <Link to={to} className="admin-action-link">
                {linkLabel}
                <AdminIcon name="arrow" />
            </Link>
        </article>
    );
}

function AdminIcon({ name }) {
    const paths = {
        shield: "M12 2 4 5v6c0 5.05 3.41 9.74 8 11 4.59-1.26 8-5.95 8-11V5l-8-3Zm0 2.18 6 2.25V11c0 3.92-2.47 7.69-6 8.86C8.47 18.69 6 14.92 6 11V6.43l6-2.25Zm-1 3.82h2v5h-2V8Zm0 7h2v2h-2v-2Z",
        refresh: "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.74 10h-2.09A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h8V3l-3.35 3.35Z",
        users: "M9.5 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm6.25-1a2.75 2.75 0 1 0 0-5.5 2.75 2.75 0 0 0 0 5.5ZM3 19.25C3 15.8 5.91 13 9.5 13s6.5 2.8 6.5 6.25V20H3v-.75Zm13.37-6.09A6.95 6.95 0 0 1 18 17.7V20h3v-.6c0-3.01-1.98-5.55-4.63-6.24Z",
        admin: "M12 2 5 5v6c0 4.42 2.99 8.52 7 9.62 4.01-1.1 7-5.2 7-9.62V5l-7-3Zm0 4a3 3 0 1 1 0 6 3 3 0 0 1 0-6Zm-4 9.45c.75-1.37 2.24-2.2 4-2.2s3.25.83 4 2.2A7.72 7.72 0 0 1 12 18.5a7.72 7.72 0 0 1-4-3.05Z",
        analysis: "M4 19V9h3v10H4Zm6 0V5h3v14h-3Zm6 0v-7h3v7h-3Z",
        complete: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-1.2 14.2-4-4 1.4-1.4 2.6 2.59 5-5 1.4 1.42-6.4 6.39Z",
        storage: "M12 3C7.58 3 4 4.34 4 6v12c0 1.66 3.58 3 8 3s8-1.34 8-3V6c0-1.66-3.58-3-8-3Zm0 2c3.31 0 5.46.73 5.94 1-.48.27-2.63 1-5.94 1s-5.46-.73-5.94-1c.48-.27 2.63-1 5.94-1Zm0 14c-3.53 0-5.74-.83-6-1.16v-2.03C7.47 16.56 9.64 17 12 17s4.53-.44 6-1.19v2.01c-.26.35-2.47 1.18-6 1.18Zm0-4c-3.53 0-5.74-.83-6-1.16v-2.03C7.47 12.56 9.64 13 12 13s4.53-.44 6-1.19v2.01c-.26.35-2.47 1.18-6 1.18Zm0-4c-3.53 0-5.74-.83-6-1.16V8.01C7.47 8.65 9.56 9 12 9s4.53-.35 6-.99v1.81c-.26.35-2.47 1.18-6 1.18Z",
        email: "M20 4H4a2 2 0 0 0-1.99 2L2 18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 4-8 5-8-5V6l8 5 8-5v2Z",
        recovery: "M12 3a9 9 0 0 0-8.52 6H1l3.2 3.2L7.4 9H5.6A7 7 0 1 1 6 16.45l-1.43 1.4A9 9 0 1 0 12 3Zm-1 5v5l4.25 2.52.75-1.23-3.5-2.04V8H11Z",
        audit: "M7 3h10v2h3v16H4V5h3V3Zm2 2h6V4H9v1Zm-2 2v12h10V7H7Zm2 2h6v2H9V9Zm0 4h6v2H9v-2Z",
        info: "M11 10h2v7h-2v-7Zm0-3h2v2h-2V7Zm1-5a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16Z",
        arrow: "m13.17 5.59 1.42-1.42L22.41 12l-7.82 7.83-1.42-1.42L18.59 13H2v-2h16.59l-5.42-5.41Z",
    };

    return (
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d={paths[name]} />
        </svg>
    );
}

export default AdminDashboardPage;
