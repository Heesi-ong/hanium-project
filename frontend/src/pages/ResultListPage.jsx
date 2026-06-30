function ResultListPage() {
    return (
        <section className="page-section">
            <div className="page-header">
                <p className="eyebrow">Results</p>
                <h1>분석 결과 목록</h1>
                <p>
                    이 페이지는 다음 단계에서 백엔드의 <code>GET /api/results</code>{" "}
                    API와 연결됩니다.
                </p>
            </div>

            <div className="placeholder-card">
                <h2>결과 목록 준비 단계</h2>
                <p>
                    이후 이 화면에서 분석 작업 목록, 상태, 파일명, 완료 시간, 삭제 기능을
                    표시합니다.
                </p>
            </div>
        </section>
    );
}

export default ResultListPage;