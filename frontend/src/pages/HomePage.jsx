import { Link } from "react-router-dom";

function HomePage() {
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
        </section>
    );
}

export default HomePage;