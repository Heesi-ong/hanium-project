// 발표 목적 입력과 영상 업로드를 받아 백엔드 분석 작업을 생성하는 페이지다.
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { getPracticePurposes, getPracticeSeries } from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";
import PracticeContextForm from "../features/upload/PracticeContextForm";
import useAnalysisUpload, { MAX_FILE_SIZE_MB } from "../features/upload/useAnalysisUpload";

import "./UploadPage.css";

const analysisItems = [
  {
    label: "자세",
    value: "Pose",
    description: "어깨 기울기, 몸 중심, 발표 자세 안정성을 분석합니다.",
  },
  {
    label: "얼굴 방향",
    value: "Head direction",
    description: "얼굴 특징점으로 정면 방향 안정성을 보조 분석합니다.",
  },
  {
    label: "말하기",
    value: "Speech",
    description: "말하기 속도, 침묵 구간, 필러 단어 사용을 분석합니다.",
  },
  {
    label: "손동작",
    value: "Gesture",
    description: "손의 움직임 빈도와 발표 중 제스처 사용을 확인합니다.",
  },
];

const uploadGuides = [
  "발표자가 화면에 명확하게 보이는 영상을 사용하세요.",
  "소리가 포함된 영상일수록 말하기 분석 결과가 정확합니다.",
  "너무 어둡거나 흔들림이 심한 영상은 분석 정확도가 낮아질 수 있습니다.",
  "업로드 후 다른 화면으로 이동해도 서버에서 분석이 계속 진행됩니다.",
];
const fallbackPurposes = [
  {
    key: "project",
    label: "프로젝트 발표",
    focus: "문제·해결 과정·결과와 기여도를 전달",
    recommended_minutes: 12,
  },
];

const formatFileSize = (size) => {
  if (!size) return "0 MB";

  const sizeMB = size / 1024 / 1024;
  return `${sizeMB.toFixed(2)} MB`;
};

function UploadPage() {
  const navigate = useNavigate();
  const [purposes, setPurposes] = useState(fallbackPurposes);
  const [practiceSeries, setPracticeSeries] = useState([]);
  const [practiceContext, setPracticeContext] = useState({
    purpose: "project",
    audience: "",
    target_minutes: 12,
    core_message: "",
    series_name: "",
    series_id: null,
  });
  const {
    activeJob,
    cancelUpload,
    error,
    file,
    fileInputRef,
    loading,
    pollWarning,
    processMessage,
    processStep,
    resetSelectedFile,
    result,
    selectFile,
    startUpload,
    uploadProgress,
  } = useAnalysisUpload(practiceContext, navigate);

  useEffect(() => {
    const controller = new AbortController();
    getPracticePurposes(controller.signal)
      .then((response) =>
        setPurposes(response.purposes?.length ? response.purposes : fallbackPurposes),
      )
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setPurposes(fallbackPurposes);
      });
    getPracticeSeries(controller.signal)
      .then((response) => setPracticeSeries(response.series || []))
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setPracticeSeries([]);
      });
    return () => controller.abort();
  }, []);

  const handleFileChange = (event) => {
    selectFile(event.target.files[0]);
  };

  const moveToResult = () => {
    const resultId = result?.result?.result_id;

    if (!resultId) {
      return;
    }

    navigate(`/result/${resultId}`);
  };

  return (
    <div className="page upload-page">
      <section className="upload-hero">
        <div className="upload-hero-content">
          <span className="upload-section-kicker">Presentation analysis</span>
          <h1>발표 영상 분석</h1>
          <p>
            영상 하나로 자세, 시선, 말하기 흐름을 확인하고 다음 연습에서 바로 바꿀 행동까지
            연결합니다.
          </p>

          <div className="upload-hero-meta" aria-label="업로드 분석 요약">
            <span>최대 {MAX_FILE_SIZE_MB}MB</span>
            <span>MP4 · MOV · AVI · MKV</span>
            <span>분석 후 결과 자동 저장</span>
          </div>
        </div>

        <div className="upload-hero-panel" aria-label="분석 진행 순서">
          <span className="upload-hero-panel-label">Flow</span>
          <ol>
            <li>연습 목표 설정</li>
            <li>발표 영상 업로드</li>
            <li>결과 확인 및 다음 연습</li>
          </ol>
        </div>
      </section>

      <div className="card upload-analysis-card">
        <div className="upload-card-header">
          <span className="upload-section-kicker">Analysis scope</span>
          <h2>분석 항목</h2>
        </div>

        <div className="metric-grid">
          {analysisItems.map((item) => (
            <div className="metric-item upload-analysis-item" key={item.value}>
              <div className="metric-label">{item.label}</div>

              <div className="metric-value">{item.value}</div>

              <p className="upload-description">{item.description}</p>
            </div>
          ))}
        </div>

        <p className="upload-description upload-description-large">
          발표 영상을 업로드하면 자세, 얼굴 방향, 말하기 속도, 침묵 구간, 필러 단어, 손동작, 음량을
          종합 분석합니다.
        </p>
      </div>

      <PracticeContextForm
        context={practiceContext}
        loading={loading}
        practiceSeries={practiceSeries}
        purposes={purposes}
        setContext={setPracticeContext}
      />

      <div className="card upload-card">
        <div className="upload-card-header">
          <div>
            <span className="upload-section-kicker">Upload</span>
            <h2>영상 업로드</h2>
          </div>

          <p>업로드 가능 형식: MP4, MOV, AVI, MKV / 최대 {MAX_FILE_SIZE_MB}MB</p>
        </div>

        <div className="upload-workspace">
          <div className="upload-file-box">
            <span className="upload-file-icon" aria-hidden="true">
              ↑
            </span>
            <label className="metric-value upload-file-title" htmlFor="presentation-video">
              발표 영상 파일 선택
            </label>

            <p className="upload-file-description">
              발표자가 화면에 잘 보이고 음성이 포함된 영상을 선택하세요.
            </p>

            <input
              id="presentation-video"
              ref={fileInputRef}
              className="upload-file-input"
              type="file"
              accept=".mp4,.mov,.avi,.mkv"
              onChange={handleFileChange}
              disabled={loading}
            />

            <div className="upload-button-area">
              <button className="button" onClick={startUpload} disabled={loading || !file}>
                {loading ? "처리 중..." : "분석 시작"}
              </button>
            </div>
            {!file && !loading && (
              <p className="upload-file-hint" role="status">
                파일을 선택하면 분석 시작 버튼이 활성화됩니다.
              </p>
            )}
          </div>

          <div className="metric-item upload-guide-box">
            <div className="metric-label">업로드 전 확인사항</div>

            <ul className="upload-guide-list">
              {uploadGuides.map((guide) => (
                <li key={guide} className="upload-guide-item">
                  {guide}
                </li>
              ))}
            </ul>
          </div>
        </div>

        {file && (
          <div className="metric-item selected-file-box">
            <div>
              <div className="metric-label">선택 파일</div>

              <div className="metric-value">{file.name}</div>
            </div>

            <div>
              <div className="metric-label selected-file-size-label">파일 크기</div>

              <div className="metric-value">{formatFileSize(file.size)}</div>
            </div>

            <button
              className="button selected-file-reset-button"
              onClick={resetSelectedFile}
              disabled={loading}
            >
              선택 파일 초기화
            </button>
          </div>
        )}

        {loading && (
          <div className="upload-progress-section">
            <div className="metric-label">처리 상태</div>

            <div className="metric-value">{processMessage}</div>

            <div
              className="upload-progress-bar"
              role="progressbar"
              aria-label={processStep === "uploading" ? "영상 업로드 진행률" : "영상 분석 진행률"}
              aria-valuemin="0"
              aria-valuemax="100"
              aria-valuenow={
                processStep === "uploading" ? uploadProgress : activeJob?.progress || 0
              }
            >
              <div
                className="upload-progress-fill"
                style={{
                  width: `${processStep === "uploading" ? uploadProgress : activeJob?.progress || 0}%`,
                }}
              />
            </div>

            <p className="upload-progress-text" aria-live="polite">
              {processStep === "uploading"
                ? `업로드 진행률: ${uploadProgress}%`
                : `분석 진행률: ${activeJob?.progress || 0}%`}
            </p>

            {(processStep === "uploading" || processStep === "analyzing") && (
              <>
                {processStep === "analyzing" && (
                  <p>
                    서버에서 프레임 추출, 자세 분석, 음성 분석을 수행 중입니다. 다른 화면으로
                    이동해도 분석은 계속 진행됩니다.
                  </p>
                )}
                <button className="button danger" onClick={cancelUpload}>
                  {processStep === "uploading" ? "업로드 취소" : "분석 취소"}
                </button>
              </>
            )}
          </div>
        )}

        {error && (
          <StateMessage type="error" compact>
            {error}
          </StateMessage>
        )}

        {pollWarning && !error && <StateMessage compact>{pollWarning}</StateMessage>}

        {processStep === "cancelled" && (
          <StateMessage type="empty" compact>
            {activeJob
              ? "분석 작업을 취소했습니다. 분석 이력에서 다시 시도할 수 있습니다."
              : "영상 업로드를 취소했습니다."}
          </StateMessage>
        )}
      </div>

      {result && (
        <div
          className={
            result?.summary?.status === "FAILED"
              ? "card upload-result-card upload-result-card-failed"
              : "card upload-result-card upload-result-card-success"
          }
        >
          <div className="upload-result-header">
            <div>
              <h2>{result?.summary?.status === "FAILED" ? "분석 실패" : "분석 완료"}</h2>

              <p className="upload-result-subtitle">
                {result?.summary?.status === "FAILED"
                  ? "영상 분석을 완료하지 못했습니다."
                  : "영상 분석 결과가 생성되었습니다."}
              </p>
            </div>
          </div>

          {result?.summary?.status === "FAILED" ? (
            <p className="error-text">{result?.summary?.error}</p>
          ) : (
            <>
              <div className="metric-grid">
                <div className="metric-item">
                  <div className="metric-label">총점</div>

                  <div className="metric-value">{result?.summary?.total_score ?? "측정 불가"}</div>
                </div>

                <div className="metric-item">
                  <div className="metric-label">처리 시간</div>

                  <div className="metric-value">{result?.summary?.processing_time_seconds}초</div>
                </div>
              </div>

              <p className="upload-description upload-description-large">
                {result?.summary?.summary_feedback}
              </p>

              <button className="button" onClick={moveToResult}>
                상세 결과 보기
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default UploadPage;
