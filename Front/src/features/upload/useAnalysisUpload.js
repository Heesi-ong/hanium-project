// 영상 업로드, 작업 폴링, 취소, 완료 후 결과 이동까지 업로드 흐름을 관리한다.
import { useEffect, useRef, useState } from "react";

import { cancelAnalyzeJob, getAnalyzeJob, savePracticeContext } from "../../api/analyzeApi";
import { uploadAnalyzeVideo } from "../../api/uploadApi";
import { analysisStageLabels } from "../analysis/stages";

export const MAX_FILE_SIZE_MB = 500;
export const MAX_ANALYSIS_POLL_FAILURES = 3;
const MAX_FILE_SIZE = MAX_FILE_SIZE_MB * 1024 * 1024;

export default function useAnalysisUpload(practiceContext, navigate) {
  const fileInputRef = useRef(null);
  const uploadControllerRef = useRef(null);
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [processStep, setProcessStep] = useState("idle");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [pollWarning, setPollWarning] = useState("");
  const [activeJobId, setActiveJobId] = useState("");
  const [activeJob, setActiveJob] = useState(null);
  const pollFailureCountRef = useRef(0);

  useEffect(() => () => uploadControllerRef.current?.abort(), []);

  useEffect(() => {
    if (!activeJobId || processStep !== "analyzing") return undefined;

    let cancelled = false;
    let requestController = null;
    const pollJob = async () => {
      if (document.hidden) return;
      requestController?.abort();
      requestController = new AbortController();
      try {
        const response = await getAnalyzeJob(activeJobId, requestController.signal);
        const job = response.job;
        if (cancelled) return;
        pollFailureCountRef.current = 0;
        setPollWarning("");
        setActiveJob(job);

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
      } catch (requestError) {
        if (!cancelled && requestError.name !== "AbortError") {
          pollFailureCountRef.current += 1;
          if (pollFailureCountRef.current < MAX_ANALYSIS_POLL_FAILURES) {
            setPollWarning("분석 상태를 일시적으로 확인하지 못했습니다. 자동으로 다시 시도합니다.");
            return;
          }
          setProcessStep("error");
          setPollWarning("");
          setError(requestError.message || "분석 상태를 확인하지 못했습니다.");
          setLoading(false);
        }
      }
    };

    void pollJob();
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
    setPollWarning("");
    pollFailureCountRef.current = 0;
    setUploadProgress(0);
    setProcessStep("idle");
    setActiveJobId("");
    setActiveJob(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const selectFile = (selectedFile) => {
    if (!selectedFile) return;
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
    setPollWarning("");
    pollFailureCountRef.current = 0;
  };

  const startUpload = async () => {
    if (loading) return;
    if (!file) {
      setError("분석할 영상 파일을 선택해주세요.");
      return;
    }

    try {
      setLoading(true);
      setError("");
      setPollWarning("");
      setResult(null);
      setUploadProgress(0);
      setProcessStep("uploading");
      const controller = new AbortController();
      uploadControllerRef.current = controller;
      const response = await uploadAnalyzeVideo(
        file,
        (percent) => {
          setUploadProgress(percent);
        },
        controller.signal,
      );
      uploadControllerRef.current = null;
      const resultId = response?.job?.result_id;
      if (!resultId) throw new Error("분석 작업 ID를 받지 못했습니다.");
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
    } catch (requestError) {
      uploadControllerRef.current = null;
      if (requestError.name === "AbortError") {
        setProcessStep("cancelled");
        setLoading(false);
        return;
      }
      setProcessStep("error");
      setError(requestError.message || "영상 분석 중 오류가 발생했습니다.");
      setLoading(false);
    }
  };

  const cancelUpload = async () => {
    if (processStep === "uploading") {
      uploadControllerRef.current?.abort();
      return;
    }
    if (!activeJobId) return;
    try {
      await cancelAnalyzeJob(activeJobId);
      setProcessStep("cancelled");
      setLoading(false);
    } catch (requestError) {
      setError(requestError.message || "분석 작업을 취소하지 못했습니다.");
    }
  };

  const processMessage = {
    uploading: "영상을 서버로 업로드하고 있습니다.",
    analyzing:
      analysisStageLabels[activeJob?.stage] || "백그라운드에서 영상 분석을 진행하고 있습니다.",
    done: "분석이 완료되었습니다.",
    error: "처리 중 오류가 발생했습니다.",
    cancelled: "분석 작업이 취소되었습니다.",
  }[processStep];

  return {
    activeJob,
    cancelUpload,
    error,
    file,
    fileInputRef,
    loading,
    processMessage,
    processStep,
    pollWarning,
    resetSelectedFile,
    result,
    selectFile,
    startUpload,
    uploadProgress,
  };
}
