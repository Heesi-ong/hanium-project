import { useEffect, useState } from "react";
import { getServiceStatus } from "../api/analysisApi";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";

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
        <section className="page-section">
            <section className="engine-dashboard">
                <div className="section-title-row">
                    <PageHeader
                        eyebrow="Service Status"
                        title="서비스 상태"
                        description="현재 각 테스트 기능을 이용할 수 있는지 확인합니다. 상세 분석 단계와 오류는 터미널의 로컬 로그에서 확인합니다."
                    />

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadStatus}
                        disabled={loading}
                    >
                        {loading ? "확인 중..." : "상태 새로고침"}
                    </button>
                </div>

                <StateMessage type="error">{error}</StateMessage>

                {status && (
                    <div className="engine-card-header status-overview">
                        <div>
                            <h3>전체 상태</h3>
                            <p>마지막 확인: {formatCheckedAt(checkedAt)}</p>
                        </div>
                        <span className={getStatusClassName(overallStatus)}>
                            {STATUS_LABELS[overallStatus] || "확인 불가"}
                        </span>
                    </div>
                )}

                <div className="engine-grid">
                    {COMPONENTS.map((componentDefinition) => {
                        const componentStatus = status?.[componentDefinition.key];
                        const availability = componentStatus?.status;

                        return (
                            <article className="engine-card" key={componentDefinition.key}>
                                <div className="engine-card-header">
                                    <div>
                                        <h3>{componentDefinition.title}</h3>
                                        <p>{componentDefinition.description}</p>
                                    </div>

                                    <span className={getStatusClassName(availability)}>
                                        {loading
                                            ? "확인 중"
                                            : STATUS_LABELS[availability] || "확인 불가"}
                                    </span>
                                </div>

                                {componentStatus?.message && (
                                    <p className="engine-status-message">{componentStatus.message}</p>
                                )}
                            </article>
                        );
                    })}
                </div>
            </section>
        </section>
    );
}

export default StatusPage;
