import { useEffect, useState } from "react";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { engineHealthCheck, healthCheck } from "../api/analysisApi";

function StatusPage() {
    const [backendHealth, setBackendHealth] = useState(null);
    const [engineHealth, setEngineHealth] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {
        loadHealthStatus();
    }, []);

    async function loadHealthStatus() {
        try {
            setLoading(true);
            setError("");

            const [backendResponse, engineResponse] = await Promise.all([
                healthCheck(),
                engineHealthCheck(),
            ]);

            setBackendHealth(backendResponse.data);
            setEngineHealth(engineResponse.data);
        } catch (requestError) {
            setError(
                requestError.message ||
                "서버 상태를 확인하는 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    function getBackendStatus() {
        if (!backendHealth) {
            return "unknown";
        }

        return backendHealth.status || "unknown";
    }

    function getEngineStatus(engineName) {
        const health = engineHealth?.[engineName]?.health;

        if (!health) {
            return "unknown";
        }

        return health.status || "unknown";
    }

    function getEngineReachable(engineName) {
        const health = engineHealth?.[engineName]?.health;

        if (!health) {
            return false;
        }

        return health.reachable === true || health.status === "up";
    }

    function getEngineReadiness(engineName) {
        return engineHealth?.[engineName]?.readiness;
    }

    function getEngineDisplayStatus(engineName) {
        const status = getEngineStatus(engineName);
        const readiness = getEngineReadiness(engineName);

        if (status === "down" || !getEngineReachable(engineName)) {
            return status === "unknown" ? "unknown" : "down";
        }

        if (readiness && readiness.ready === false) {
            return "degraded";
        }

        return status;
    }

    function formatBoolean(value) {
        if (value === true) {
            return "true";
        }

        if (value === false) {
            return "false";
        }

        return "-";
    }

    function getReadinessReason(readiness) {
        return readiness?.response?.reason || readiness?.message || readiness?.error || "";
    }

    function getStatusClassName(status) {
        if (status === "ok" || status === "up") {
            return "engine-status-badge up";
        }

        if (status === "degraded") {
            return "engine-status-badge warning";
        }

        if (status === "down") {
            return "engine-status-badge down";
        }

        return "engine-status-badge unknown";
    }

    function renderEngineCard({
                                  title,
                                  description,
                                  status,
                                  baseUrl,
                                  reachable,
                                  readiness,
                              }) {
        const readinessReason = getReadinessReason(readiness);

        return (
            <article className="engine-card">
                <div className="engine-card-header">
                    <div>
                        <h3>{title}</h3>
                        <p>{description}</p>
                    </div>

                    <span className={getStatusClassName(status)}>{status}</span>
                </div>

                <div className="engine-meta">
                    <div>
                        <span>Base URL</span>
                        <strong>{baseUrl || "-"}</strong>
                    </div>

                    <div>
                        <span>Reachable</span>
                        <strong>{reachable ? "true" : "false"}</strong>
                    </div>

                    {readiness && (
                        <>
                            <div>
                                <span>Ready</span>
                                <strong>{formatBoolean(readiness.ready)}</strong>
                            </div>

                            <div>
                                <span>Authenticated</span>
                                <strong>{formatBoolean(readiness.authenticated)}</strong>
                            </div>

                            {readiness.response?.mode && (
                                <div>
                                    <span>Mode</span>
                                    <strong>{readiness.response.mode}</strong>
                                </div>
                            )}

                            {readiness.response?.realModelReady !== undefined && (
                                <div>
                                    <span>Real Model</span>
                                    <strong>{formatBoolean(readiness.response.realModelReady)}</strong>
                                </div>
                            )}

                            {readinessReason && (
                                <div>
                                    <span>Reason</span>
                                    <strong className="engine-meta-reason">
                                        {readinessReason}
                                    </strong>
                                </div>
                            )}
                        </>
                    )}
                </div>
            </article>
        );
    }

    return (
        <section className="page-section">
            <section className="engine-dashboard">
                <div className="section-title-row">
                    <PageHeader
                        eyebrow="System Status"
                        title="시스템 상태"
                        description="백엔드, 기본 분석 엔진, Video LLM 엔진의 연결 상태를 확인합니다."
                    />

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadHealthStatus}
                        disabled={loading}
                    >
                        {loading ? "확인 중..." : "상태 새로고침"}
                    </button>
                </div>

                <StateMessage type="error">{error}</StateMessage>

                <div className="engine-grid">
                    {renderEngineCard({
                        title: "Spring Boot Backend",
                        description: "업로드, 분석 상태 관리, 결과 저장/조회 API를 담당합니다.",
                        status: getBackendStatus(),
                        baseUrl: "http://localhost:8080",
                        reachable: getBackendStatus() === "ok",
                    })}

                    {renderEngineCard({
                        title: "Analysis Engine",
                        description: "음성, 자세, 시선, 필러 표현 등 기본 분석을 담당합니다.",
                        status: getEngineDisplayStatus("analysisEngine"),
                        baseUrl: engineHealth?.analysisEngine?.baseUrl,
                        reachable: getEngineReachable("analysisEngine"),
                        readiness: getEngineReadiness("analysisEngine"),
                    })}

                    {renderEngineCard({
                        title: "Video LLM Engine",
                        description: "영상 흐름 기반의 시각적 발표 태도 분석을 담당합니다.",
                        status: getEngineDisplayStatus("videoLlmEngine"),
                        baseUrl: engineHealth?.videoLlmEngine?.baseUrl,
                        reachable: getEngineReachable("videoLlmEngine"),
                        readiness: getEngineReadiness("videoLlmEngine"),
                    })}
                </div>
            </section>
        </section>
    );
}

export default StatusPage;
