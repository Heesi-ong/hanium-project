import { useParams } from "react-router-dom";

function ResultDetailPage() {
    const { jobId } = useParams();

    return (
        <section className="page-section">
            <div className="page-header">
                <p className="eyebrow">Result Detail</p>
                <h1>분석 결과 상세</h1>
                <p>
                    현재 조회 대상 jobId: <code>{jobId}</code>
                </p>
            </div>

            <div className="placeholder-card">
                <h2>상세 결과 준비 단계</h2>
                <p>
                    이후 이 화면에서 <code>GET /api/results/{"{jobId}"}</code> API를
                    호출해 점수, 피드백, 연습 계획, 타임라인 피드백을 표시합니다.
                </p>
            </div>
        </section>
    );
}

export default ResultDetailPage;