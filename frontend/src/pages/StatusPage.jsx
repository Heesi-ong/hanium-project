import { useEffect, useState } from "react";
import { getServiceStatus } from "../api/analysisApi";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import PageFadeIn from "../components/motion/PageFadeIn";

const STATUS_LABELS = {
    AVAILABLE: "정상",
    DEGRADED: "일부 제한",
    UNAVAILABLE: "이용 불가",
};

const COMPONENTS = [
    {
        key: "backend",
        title: "서비스 연결",
        description: "업로드, 분석 상태 관리, 결과 조회 기능의 연결 상태입니다.",
    },
    {
        key: "analysisEngine",
        title: "기본 분석",
        description: "음성, 자세, 제스처, 필러 표현 분석 기능의 이용 가능 여부입니다.",
    },
    {
        key: "videoLlmEngine",
        title: "Video LLM 분석",
        description: "영상 흐름 기반 발표 태도 분석 기능의 이용 가능 여부입니다.",
    },
    {
        key: "aiFeedback",
        title: "AI 피드백",
        description: "분석 결과를 바탕으로 한 코칭 피드백 생성 기능의 이용 가능 여부입니다.",
    },
    {
        key: "passwordReset",
        title: "비밀번호 재설정",
        description: "이메일을 통한 계정 복구 기능의 이용 가능 여부입니다.",
    },
];

function getStatusClassName(status) {
    if (status === "AVAILABLE") {
        return "engine-status-badge up";
    }

    if (status === "DEGRADED") {
        return "engine-status-badge warning";
    }

    if (status === "UNAVAILABLE") {
        return "engine-status-badge down";
    }

    return "engine-status-badge unknown";
}

function getStatusSymbol(status, loading = false) {
    if (loading) {
        return "···";
    }

    if (status === "AVAILABLE") {
        return "✓";
    }

    if (status === "DEGRADED") {
        return "!";
    }

    if (status === "UNAVAILABLE") {
        return "×";
    }

    return "?";
}

function getStatusLabel(status, loading = false) {
    if (loading) {
        return "확인 중";
    }

    return STATUS_LABELS[status] || "확인 불가";
}

function StatusBadge({ status, loading = false }) {
    return (
        <span className={getStatusClassName(status)}>
            <span className="engine-status-symbol" aria-hidden="true">
                {getStatusSymbol(status, loading)}
            </span>
            {getStatusLabel(status, loading)}
        </span>
    );
}

function formatCheckedAt(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "-";
    }

    return date.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
    });
}

function StatusPage() {
    const [status, setStatus] = useState(null);
    const [checkedAt, setCheckedAt] = useState("");
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {
        loadStatus();
    }, []);

    async function loadStatus() {
        try {
            setLoading(true);
            setError("");

            const response = await getServiceStatus();
            setStatus(response.data);
            setCheckedAt(response.timestamp);
        } catch (requestError) {
            setError(
                requestError.message ||
                "서비스 상태를 확인하는 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    const overallStatus = status?.overallStatus;

    return (
        <PageFadeIn className="status-page">
            <section className="status-hero">
                <div className="status-hero-copy">
                    <PageHeader
                        eyebrow="Service Status"
                        title="서비스 상태"
                        description="현재 각 테스트 기능을 이용할 수 있는지 확인합니다. 상세 분석 단계와 오류는 터미널의 로컬 로그에서 확인합니다."
                    />
                    <button
                        type="button"
                        className="secondary-button status-refresh-button"
                        onClick={loadStatus}
                        disabled={loading}
                    >
                        <span aria-hidden="true">↻</span>
                        {loading ? "확인 중..." : "상태 새로고침"}
                    </button>
                </div>

                <div
                    className="status-overview-card"
                    data-status={overallStatus || (loading ? "LOADING" : "UNKNOWN")}
                    role="status"
                    aria-live="polite"
                >
                    <div className="status-overview-orbit" aria-hidden="true">
                        <span />
                        <span />
                        <span />
                    </div>
                    <div className="status-overview-copy">
                        <span>전체 시스템</span>
                        <strong>
                            {!status && !loading
                                ? "상태 확인 필요"
                                : getStatusLabel(overallStatus, loading)}
                        </strong>
                        <small>
                            {status
                                ? `마지막 확인 ${formatCheckedAt(checkedAt)}`
                                : loading
                                    ? "각 기능의 연결 상태를 확인하고 있습니다."
                            : "새로고침하여 현재 상태를 확인하세요."}
                        </small>
                    </div>
                    {(status || loading) && (
                        <StatusBadge status={overallStatus} loading={loading} />
                    )}
                </div>
            </section>

            <section className="status-dashboard" aria-labelledby="status-components-title">
                <StateMessage type="error">{error}</StateMessage>

                <div className="status-section-heading">
                    <div>
                        <span className="status-section-index" aria-hidden="true">01</span>
                        <div>
                            <h2 id="status-components-title">기능별 연결 상태</h2>
                            <p>분석 흐름을 구성하는 다섯 가지 기능을 개별적으로 확인합니다.</p>
                        </div>
                    </div>
                    <span className="status-component-count">5 components</span>
                </div>

                <div className="engine-grid">
                    {COMPONENTS.map((componentDefinition, index) => {
                        const componentStatus = status?.[componentDefinition.key];
                        const availability = componentStatus?.status;

                        return (
                            <article
                                className="engine-card"
                                data-status={availability || (loading ? "LOADING" : "UNKNOWN")}
                                key={componentDefinition.key}
                            >
                                <span className="engine-card-index" aria-hidden="true">
                                    {String(index + 1).padStart(2, "0")}
                                </span>
                                <div className="engine-card-header">
                                    <div>
                                        <h3>{componentDefinition.title}</h3>
                                        <p>{componentDefinition.description}</p>
                                    </div>

                                    <StatusBadge status={availability} loading={loading} />
                                </div>

                                <p className="engine-status-message">
                                    {componentStatus?.message || (
                                        loading
                                            ? "현재 이용 가능 여부를 확인하고 있습니다."
                                            : "상태 정보를 확인할 수 없습니다."
                                    )}
                                </p>
                            </article>
                        );
                    })}
                </div>
            </section>
        </PageFadeIn>
    );
}

export default StatusPage;
