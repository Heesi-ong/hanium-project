function UploadPage() {
    return (
        <section className="page-section">
            <div className="page-header">
                <p className="eyebrow">Upload</p>
                <h1>발표 영상 업로드</h1>
                <p>
                    이 페이지는 다음 단계에서 백엔드의{" "}
                    <code>POST /api/analysis/upload</code> API와 연결됩니다.
                </p>
            </div>

            <div className="placeholder-card">
                <h2>업로드 기능 준비 단계</h2>
                <p>
                    이후 이 화면에서 영상 파일 선택, 업로드, 분석 실행, 상태 확인까지
                    연결합니다.
                </p>
            </div>
        </section>
    );
}

export default UploadPage;