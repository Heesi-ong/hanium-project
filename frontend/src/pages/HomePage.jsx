import { Link } from "react-router-dom";
import PageHeader from "../components/PageHeader";

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

            <section>
                <PageHeader
                    eyebrow="How it works"
                    title="이용 방법"
                    description="발표 영상을 업로드하고 분석을 실행하면, 상태와 진행률을 확인한 뒤 결과 상세 화면에서 지표와 피드백을 확인합니다."
                />

                <div className="how-it-works-grid">
                    <article className="step-card">
                        <span className="step-number">1</span>
                        <h3>영상 업로드</h3>
                        <p>mp4, mov, avi, mkv 형식의 발표 영상을 선택해 분석 작업을 생성합니다.</p>
                    </article>

                    <article className="step-card">
                        <span className="step-number">2</span>
                        <h3>자동 분석 진행</h3>
                        <p>기본 분석 엔진이 정량 지표를 추출하고, 선택한 옵션에 따라 Video LLM과 OpenAI 피드백 단계가 이어집니다.</p>
                    </article>

                    <article className="step-card">
                        <span className="step-number">3</span>
                        <h3>결과 확인</h3>
                        <p>상태와 진행률을 폴링한 뒤 완료되면 자세, 시선, 음성, 제스처, 표정 점수를 결과 화면에서 확인합니다.</p>
                    </article>

                    <article className="step-card">
                        <span className="step-number">4</span>
                        <h3>AI 피드백 확인</h3>
                        <p>분석 결과를 바탕으로 생성된 코칭 문장과 연습 계획, 타임라인 피드백을 함께 검토합니다.</p>
                    </article>
                </div>
            </section>

            <section>
                <PageHeader
                    eyebrow="Analysis Coverage"
                    title="분석 항목 상세 소개"
                    description="결과 상세 화면에서 확인할 수 있는 실제 분석 지표를 기준으로 구성했습니다."
                />

                <div className="analysis-detail-grid">
                    <article className="analysis-detail-card">
                        <h3>자세·제스처</h3>
                        <p>자세 점수, 어깨 균형, 자세 검출률과 함께 제스처 비율, 손 검출률, 손목 움직임을 확인합니다.</p>
                    </article>

                    <article className="analysis-detail-card">
                        <h3>시선·얼굴</h3>
                        <p>얼굴 검출률, 카메라 응시 비율, 아이컨택 수준과 프레임별 시선 분석 결과를 제공합니다.</p>
                    </article>

                    <article className="analysis-detail-card">
                        <h3>표정·감정</h3>
                        <p>표정 점수, 표현력 원점수, 표정 다양성 점수와 주요 표정 상태 집계를 함께 보여줍니다.</p>
                    </article>

                    <article className="analysis-detail-card">
                        <h3>음성·발화</h3>
                        <p>WPM, 단어 수, 발화·침묵 시간, 침묵 비율, 필러 표현과 STT transcript를 확인합니다.</p>
                    </article>
                </div>
            </section>

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
