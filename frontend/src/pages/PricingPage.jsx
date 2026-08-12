import { Link } from "react-router-dom";

function PricingPage() {
    return (
        <section className="page-section">
            <article className="upload-card policy-card">
                <p className="eyebrow">Beta</p>
                <h2>베타 이용 안내</h2>

                <div className="policy-disclaimer">
                    현재는 결제·유료 요금제가 없는 베타 서비스입니다. 실제 요금, 결제 수단, 등급별
                    제공 범위는 서비스 정식 출시 시점에 다시 안내해드립니다.
                </div>

                <div className="policy-section">
                    <h3>현재 이용 안내</h3>
                    <p>
                        지금은 결제 기능이 연동되어 있지 않으며, 로그인한 사용자는 발표 영상 분석과
                        AI 코치 대화 등 모든 기능을 별도 요금 없이 이용할 수 있습니다. 다만 어뷰징
                        방지를 위한 요청 빈도 제한(rate limit)은 적용되어 있습니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>제공 범위와 한계</h3>
                    <p>
                        자세·시선·제스처·표정·음성 분석은 업로드된 영상 품질과 촬영 각도에 따라
                        정확도가 달라질 수 있으며, 의학적·심리적 진단이 아닌 발표 연습 참고용
                        지표입니다. AI 코칭 문장은 OpenAI/Video LLM 등 외부 AI 모델 호출 결과이며,
                        모델 호출에 실패하면 내부 기본(Mock) 로직으로 대체되어 안내됩니다.
                    </p>
                    <p className="policy-links">
                        <Link to="/results">내 분석 결과 목록에서 예시 확인하기</Link>
                    </p>
                </div>

                <div className="policy-section">
                    <h3>앞으로의 계획</h3>
                    <p>
                        서비스 운영 비용(OpenAI, Video LLM 등 외부 AI API 사용료 포함)을 고려해 유료
                        요금제 도입을 검토하고 있습니다. 요금제 구성과 가격이 확정되면 이 페이지를 통해
                        미리 안내한 뒤 적용합니다.
                    </p>
                </div>

                <p className="policy-links">
                    <Link to="/upload">지금 발표 분석 시작하기</Link>
                </p>
            </article>
        </section>
    );
}

export default PricingPage;
