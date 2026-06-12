import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  cancelAnalyzeJob,
  getAnalyzeJob,
  getPracticePurposes,
  savePracticeContext,
  uploadAnalyzeVideo,
} from "../api/analyzeApi";
import StateMessage from "../components/StateMessage";
import { analysisStageLabels as stageLabels } from "../features/analysis/stages";

import "./UploadPage.css";

const MAX_FILE_SIZE_MB = 500;
const MAX_FILE_SIZE = MAX_FILE_SIZE_MB * 1024 * 1024;
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
  const fileInputRef = useRef(null);

  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [processStep, setProcessStep] = useState("idle");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [activeJobId, setActiveJobId] = useState("");
  const [activeJob, setActiveJob] = useState(null);
  const [purposes, setPurposes] = useState(fallbackPurposes);
  const [practiceContext, setPracticeContext] = useState({
    purpose: "project",
    audience: "",
    target_minutes: 12,
    core_message: "",
    series_name: "",
  });

  useEffect(() => {
    const controller = new AbortController();
    getPracticePurposes(controller.signal)
      .then((response) =>
        setPurposes(response.purposes?.length ? response.purposes : fallbackPurposes),
      )
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setPurposes(fallbackPurposes);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!activeJobId || processStep !== "analyzing") {
      return undefined;
    }

    let cancelled = false;
    let requestController = null;
    const pollJob = async () => {
      if (document.hidden) return;
      requestController?.abort();
      requestController = new AbortController();
      try {
        const response = await getAnalyzeJob(activeJobId, requestController.signal);
        const job = response.job;
        setActiveJob(job);

        if (cancelled) return;

        if (job.status === "COMPLETED") {
          setProcessStep("done");
          setResult({ result: { result_id: activeJobId }, summary: job });
          setLoading(false);
          navigate(`/result/${activeJobId}`);
        } else if (job.status === "FAILED") {
          setProcessStep("error");
          setError(job.public_error || "영상 분석을 완료하지 못했습니다.");
          setLoading(false);
        } else if (job.status === "CANCELLED") {
          setProcessStep("cancelled");
          setLoading(false);
        }
      } catch (err) {
        if (!cancelled && err.name !== "AbortError") {
          setProcessStep("error");
          setError(err.message || "분석 상태를 확인하지 못했습니다.");
          setLoading(false);
        }
      }
    };

    pollJob();
    const intervalId = window.setInterval(pollJob, 3000);
    return () => {
      cancelled = true;
      requestController?.abort();
      window.clearInterval(intervalId);
    };
  }, [activeJobId, navigate, processStep]);

  const resetSelectedFile = () => {
    setFile(null);
    setResult(null);
    setError("");
    setUploadProgress(0);
    setProcessStep("idle");
    setActiveJobId("");
    setActiveJob(null);

    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const handleFileChange = (event) => {
    const selectedFile = event.target.files[0];

    if (!selectedFile) {
      return;
    }

    if (selectedFile.size > MAX_FILE_SIZE) {
      resetSelectedFile();
      setError(`파일 크기는 최대 ${MAX_FILE_SIZE_MB}MB까지 업로드할 수 있습니다.`);
      return;
    }

    setFile(selectedFile);
    setResult(null);
    setUploadProgress(0);
    setProcessStep("idle");
    setError("");
  };

  const handleUpload = async () => {
    if (loading) {
      return;
    }

    if (!file) {
      setError("분석할 영상 파일을 선택해주세요.");
      return;
    }

    try {
      setLoading(true);
      setError("");
      setResult(null);
      setUploadProgress(0);
      setProcessStep("uploading");

      const response = await uploadAnalyzeVideo(file, (percent) => {
        setUploadProgress(percent);

        if (percent >= 100) {
          setProcessStep("analyzing");
        }
      });

      const resultId = response?.job?.result_id;
      if (!resultId) {
        throw new Error("분석 작업 ID를 받지 못했습니다.");
      }
      setActiveJobId(resultId);
      setActiveJob(response.job);
      try {
        await savePracticeContext(resultId, practiceContext);
      } catch {
        setError(
          "분석은 시작되었지만 연습 목표를 저장하지 못했습니다. 결과 화면에서는 기본 목표를 사용합니다.",
        );
      }
      setProcessStep("analyzing");
    } catch (err) {
      console.error(err);
      setProcessStep("error");
      setError(err.message || "영상 분석 중 오류가 발생했습니다.");
      setLoading(false);
    }
  };

  const handleCancel = async () => {
    if (!activeJobId) return;
    try {
      await cancelAnalyzeJob(activeJobId);
      setProcessStep("cancelled");
      setLoading(false);
    } catch (err) {
      setError(err.message || "분석 작업을 취소하지 못했습니다.");
    }
  };

  const moveToResult = () => {
    const resultId = result?.result?.result_id;

    if (!resultId) {
      return;
    }

    navigate(`/result/${resultId}`);
  };

  const getProcessMessage = () => {
    if (processStep === "uploading") {
      return "영상을 서버로 업로드하고 있습니다.";
    }

    if (processStep === "analyzing") {
      return stageLabels[activeJob?.stage] || "백그라운드에서 영상 분석을 진행하고 있습니다.";
    }

    if (processStep === "done") {
      return "분석이 완료되었습니다.";
    }

    if (processStep === "error") {
      return "처리 중 오류가 발생했습니다.";
    }

    if (processStep === "cancelled") {
      return "분석 작업이 취소되었습니다.";
    }

    return "";
  };

  return (
    <div className="page">
      <h1 className="page-title">발표 영상 분석</h1>

      <div className="card">
        <h2>분석 항목</h2>

        <div className="metric-grid">
          {analysisItems.map((item) => (
            <div className="metric-item" key={item.value}>
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

      <div className="card">
        <h2>이번 발표의 연습 목표</h2>
        <p className="upload-description">
          목적과 청중을 알려주면 분석 결과를 실제 다음 연습 과제로 연결합니다.
        </p>
        <div className="purpose-grid" role="radiogroup" aria-label="발표 목적">
          {purposes.map((purpose) => (
            <label
              className={`purpose-card ${practiceContext.purpose === purpose.key ? "selected" : ""}`}
              key={purpose.key}
            >
              <input
                type="radio"
                name="purpose"
                value={purpose.key}
                checked={practiceContext.purpose === purpose.key}
                disabled={loading}
                onChange={(event) =>
                  setPracticeContext((current) => ({
                    ...current,
                    purpose: event.target.value,
                    target_minutes: purpose.recommended_minutes,
                  }))
                }
              />
              <strong>{purpose.label}</strong>
              <span>{purpose.focus}</span>
            </label>
          ))}
        </div>
        <div className="practice-field-grid">
          <label>
            발표 대상
            <input
              value={practiceContext.audience}
              maxLength="120"
              placeholder="예: 전공 교수님과 팀원"
              disabled={loading}
              onChange={(event) =>
                setPracticeContext((current) => ({ ...current, audience: event.target.value }))
              }
            />
          </label>
          <label>
            목표 시간(분)
            <input
              type="number"
              min="1"
              max="180"
              value={practiceContext.target_minutes}
              disabled={loading}
              onChange={(event) =>
                setPracticeContext((current) => ({
                  ...current,
                  target_minutes: Number(event.target.value) || 1,
                }))
              }
            />
          </label>
          <label>
            반복 연습 이름
            <input
              value={practiceContext.series_name}
              maxLength="120"
              placeholder="예: 한이음 최종 발표"
              disabled={loading}
              onChange={(event) =>
                setPracticeContext((current) => ({ ...current, series_name: event.target.value }))
              }
            />
          </label>
          <label className="practice-core-message">
            반드시 전달할 핵심 메시지
            <textarea
              value={practiceContext.core_message}
              maxLength="500"
              rows="3"
              placeholder="청중이 발표 후 기억해야 할 한 문장을 입력하세요."
              disabled={loading}
              onChange={(event) =>
                setPracticeContext((current) => ({ ...current, core_message: event.target.value }))
              }
            />
          </label>
        </div>
      </div>

      <div className="card">
        <h2>영상 업로드</h2>

        <p>업로드 가능 형식: MP4, MOV, AVI, MKV / 최대 {MAX_FILE_SIZE_MB}MB</p>

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

        <div className="upload-file-box">
          <div className="metric-value upload-file-title">발표 영상 파일 선택</div>

          <p className="upload-file-description">
            MP4, MOV, AVI, MKV 형식의 영상을 업로드할 수 있습니다.
          </p>

          <input
            ref={fileInputRef}
            className="upload-file-input"
            type="file"
            accept=".mp4,.mov,.avi,.mkv"
            onChange={handleFileChange}
            disabled={loading}
          />

          <div className="upload-button-area">
            <button className="button" onClick={handleUpload} disabled={loading}>
              {loading ? "처리 중..." : "분석 시작"}
            </button>
          </div>
        </div>

        {file && (
          <div className="metric-item selected-file-box">
            <div className="metric-label">선택 파일</div>

            <div className="metric-value">{file.name}</div>

            <div className="metric-label selected-file-size-label">파일 크기</div>

            <div className="metric-value">{formatFileSize(file.size)}</div>

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

            <div className="metric-value">{getProcessMessage()}</div>

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

            {processStep === "analyzing" && (
              <>
                <p>
                  서버에서 프레임 추출, 자세 분석, 음성 분석을 수행 중입니다. 다른 화면으로 이동해도
                  분석은 계속 진행됩니다.
                </p>
                <button className="button danger" onClick={handleCancel}>
                  분석 취소
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

        {processStep === "cancelled" && (
          <StateMessage type="empty" compact>
            분석 작업을 취소했습니다. 분석 이력에서 다시 시도할 수 있습니다.
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

                  <div className="metric-value">{result?.summary?.total_score}</div>
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
