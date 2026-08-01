import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
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

    if (loading && !stats) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Admin"
                    title="관리자 업무 개요"
                    description="사용자 현황과 조치가 필요한 복구 작업을 불러오는 중입니다."
                />
                <EmptyState loading title="로딩 중" description="잠시만 기다려 주세요." />
            </section>
        );
    }

    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Admin"
                title="관리자 업무 개요"
                description="사용자·분석 데이터 관리와 수동 복구가 필요한 항목만 보여줍니다. 기술 지표와 장애 추세는 운영 모니터링에서 확인합니다."
            />

            <AdminNav />
            <StateMessage type="error">{error}</StateMessage>

            <div className="result-summary-grid">
                <SummaryCard label="전체 가입자" value={stats?.totalUsers} description="등록된 사용자" />
                <SummaryCard label="관리자" value={stats?.adminUsers} description="관리자 계정" />
                <SummaryCard label="전체 분석" value={stats?.totalAnalysisJobs} description="접수된 분석 작업" />
                <SummaryCard label="완료 분석" value={stats?.completedAnalysisJobs} description="정상 완료된 작업" />
                <SummaryCard label="분석 복구" value={recoveryCounts.analysis} description="재시도 소진 작업" attention />
                <SummaryCard label="삭제 복구" value={recoveryCounts.storageDeletion} description="미처리 파일 삭제" attention />
                <SummaryCard label="이메일 복구" value={recoveryCounts.passwordResetEmail} description="미발송 재설정 메일" attention />
            </div>

            <div className="mt-8 grid gap-4 md:grid-cols-2">
                <AdminActionCard
                    title="사용자 관리"
                    description="사용자를 검색하고 계정 상태와 소유 분석 결과를 관리합니다."
                    to="/admin/users"
                    linkLabel="사용자 관리 열기"
                />
                <AdminActionCard
                    title="복구 작업"
                    description="분석·스토리지 삭제·비밀번호 이메일 DEAD_LETTER를 검토하고 재큐잉합니다."
                    to="/admin/recovery"
                    linkLabel="복구 작업 열기"
                />
            </div>

            <div className="button-row">
                <button type="button" className="secondary-button" onClick={loadOverview} disabled={loading}>
                    {loading ? "새로고침 중..." : "개요 새로고침"}
                </button>
            </div>
        </section>
    );
}

function getTotalElements(result, errors, fallbackMessage) {
    if (result.status === "fulfilled") {
        return result.value.data?.totalElements ?? 0;
    }

    errors.push(getErrorMessage(result.reason, fallbackMessage));
    return null;
}

function SummaryCard({ label, value, description, attention = false }) {
    const numericValue = typeof value === "number" ? value : null;
    return (
        <article className="summary-card">
            <span>{label}</span>
            <strong className={attention && numericValue > 0 ? "text-warning" : undefined}>
                {numericValue ?? "-"}
            </strong>
            <p>{description}</p>
        </article>
    );
}

function AdminActionCard({ title, description, to, linkLabel }) {
    return (
        <article className="result-card">
            <h3>{title}</h3>
            <p>{description}</p>
            <Link to={to} className="secondary-button inline-flex mt-4">
                {linkLabel} →
            </Link>
        </article>
    );
}

export default AdminDashboardPage;
