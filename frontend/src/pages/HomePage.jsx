import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import { engineHealthCheck, healthCheck } from "../api/analysisApi";

function HomePage() {
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

    function getStatusClassName(status) {
        if (status === "ok" || status === "up") {
            return "engine-status-badge up";
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
                              }) {
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
                </div>
            </article>
        );
    }

    return (
        <section className="page-section">
            <div className="hero-card">
                <p className="eyebrow">AI 기반 발표 분석 서비스</p>

                <h1>
                    발표는 감이 아니라,
                    <br />
                    데이터로 개선합니다.
                </h1>

                <p className="hero-description">
                    업로드한 발표 영상을 기반으로 자세, 시선, 제스처, 음성 속도,
                    필러 표현, 침묵 구간을 분석하고 맞춤형 피드백을 제공합니다.
                </p>

                <div className="hero-actions">
                    <Link to="/upload" className="primary-button">
                        영상 업로드 시작
                    </Link>

                    <Link to="/results" className="secondary-button">
                        분석 결과 보기
                    </Link>
                </div>
            </div>

            <div className="feature-grid">
                <article className="feature-card">
                    <h3>기본 분석 엔진</h3>
                    <p>음성, 자세, 시선, 표정, 필러 표현 등 정량 데이터를 분석합니다.</p>
                </article>

                <article className="feature-card">
                    <h3>Video LLM 엔진</h3>
                    <p>영상 흐름을 기반으로 시각적 발표 태도와 개선 구간을 판독합니다.</p>
                </article>

                <article className="feature-card">
                    <h3>OpenAI 피드백</h3>
                    <p>분석 결과를 축약해 사용자가 이해하기 쉬운 코칭 문장으로 변환합니다.</p>
                </article>
            </div>

            <section className="engine-dashboard">
                <div className="section-title-row">
                    <PageHeader
                        eyebrow="System Status"
                        title="서버 및 엔진 상태"
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
                        status: getEngineStatus("analysisEngine"),
                        baseUrl: engineHealth?.analysisEngine?.baseUrl,
                        reachable: getEngineReachable("analysisEngine"),
                    })}

                    {renderEngineCard({
                        title: "Video LLM Engine",
                        description: "영상 흐름 기반의 시각적 발표 태도 분석을 담당합니다.",
                        status: getEngineStatus("videoLlmEngine"),
                        baseUrl: engineHealth?.videoLlmEngine?.baseUrl,
                        reachable: getEngineReachable("videoLlmEngine"),
                    })}
                </div>
            </section>
        </section>
    );
}

export default HomePage;