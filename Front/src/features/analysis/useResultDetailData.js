// 결과 상세 화면의 분석 데이터, 코칭, AI 코칭 생성 상태를 한 곳에서 관리한다.
import { useCallback, useEffect, useRef, useState } from "react";

import {
  createAiCoaching,
  getAiCoaching,
  getAnalyzeSections,
  getPracticeCoaching,
  getTimelineChart,
  regenerateAiCoaching,
} from "../../api/analyzeApi";

export default function useResultDetailData(resultId) {
  const [sections, setSections] = useState(null);
  const [chartData, setChartData] = useState([]);
  const [fileName, setFileName] = useState("");
  const [coaching, setCoaching] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [timelineLoading, setTimelineLoading] = useState(true);
  const [timelineError, setTimelineError] = useState("");
  const [coachingLoading, setCoachingLoading] = useState(true);
  const [coachingError, setCoachingError] = useState("");
  const [aiCoaching, setAiCoaching] = useState(null);
  const [aiCoachingLoading, setAiCoachingLoading] = useState(false);
  const [aiCoachingError, setAiCoachingError] = useState("");
  const requestControllers = useRef({});

  const startRequest = useCallback((key) => {
    requestControllers.current[key]?.abort();
    const controller = new AbortController();
    requestControllers.current[key] = controller;
    return controller;
  }, []);

  const loadCoaching = useCallback(async () => {
    const controller = startRequest("coaching");
    try {
      setCoachingLoading(true);
      setCoachingError("");
      const response = await getPracticeCoaching(resultId, controller.signal);
      if (requestControllers.current.coaching === controller)
        setCoaching(response.coaching || null);
    } catch (requestError) {
      if (
        requestError.name !== "AbortError" &&
        requestControllers.current.coaching === controller
      ) {
        setCoaching(null);
        setCoachingError(requestError.message || "연습 코칭을 불러오지 못했습니다.");
      }
    } finally {
      if (requestControllers.current.coaching === controller) setCoachingLoading(false);
    }
  }, [resultId, startRequest]);

  const loadSections = useCallback(async () => {
    const controller = startRequest("sections");
    try {
      setLoading(true);
      setError("");
      const response = await getAnalyzeSections(resultId, controller.signal);
      if (requestControllers.current.sections === controller) {
        setSections(response.sections);
        setFileName(response.original_filename || "파일명 없음");
      }
    } catch (requestError) {
      if (
        requestError.name !== "AbortError" &&
        requestControllers.current.sections === controller
      ) {
        setError(requestError.message || "분석 상세 결과를 불러오지 못했습니다.");
        setSections(null);
      }
    } finally {
      if (requestControllers.current.sections === controller) setLoading(false);
    }
  }, [resultId, startRequest]);

  const loadAiCoaching = useCallback(async () => {
    const controller = startRequest("aiCoaching");
    try {
      setAiCoachingLoading(true);
      setAiCoachingError("");
      const response = await getAiCoaching(resultId, controller.signal);
      if (requestControllers.current.aiCoaching === controller) {
        setAiCoaching(response.ai_coaching || null);
      }
    } catch (requestError) {
      if (
        requestError.name !== "AbortError" &&
        requestControllers.current.aiCoaching === controller
      ) {
        setAiCoachingError(requestError.message || "AI 코칭을 불러오지 못했습니다.");
      }
    } finally {
      if (requestControllers.current.aiCoaching === controller) setAiCoachingLoading(false);
    }
  }, [resultId, startRequest]);

  const generateAiCoaching = useCallback(
    async (regenerate = false) => {
      const controller = startRequest("aiCoaching");
      try {
        setAiCoachingLoading(true);
        setAiCoachingError("");
        const response = regenerate
          ? await regenerateAiCoaching(resultId, controller.signal)
          : await createAiCoaching(resultId, controller.signal);
        if (requestControllers.current.aiCoaching === controller) {
          setAiCoaching(response.ai_coaching || null);
        }
      } catch (requestError) {
        if (
          requestError.name !== "AbortError" &&
          requestControllers.current.aiCoaching === controller
        ) {
          setAiCoachingError(requestError.message || "AI 코칭을 생성하지 못했습니다.");
        }
      } finally {
        if (requestControllers.current.aiCoaching === controller) setAiCoachingLoading(false);
      }
    },
    [resultId, startRequest],
  );

  const loadTimeline = useCallback(async () => {
    const controller = startRequest("timeline");
    try {
      setTimelineLoading(true);
      setTimelineError("");
      const response = await getTimelineChart(resultId, controller.signal);
      if (requestControllers.current.timeline === controller) {
        setChartData(response.chart_data || []);
      }
    } catch (requestError) {
      if (
        requestError.name !== "AbortError" &&
        requestControllers.current.timeline === controller
      ) {
        setChartData([]);
        setTimelineError(requestError.message || "타임라인을 불러오지 못했습니다.");
      }
    } finally {
      if (requestControllers.current.timeline === controller) setTimelineLoading(false);
    }
  }, [resultId, startRequest]);

  const loadData = useCallback(() => {
    void loadSections();
    void loadTimeline();
    void loadCoaching();
    void loadAiCoaching();
  }, [loadAiCoaching, loadCoaching, loadSections, loadTimeline]);

  useEffect(() => {
    const controllers = requestControllers.current;
    setSections(null);
    setChartData([]);
    setCoaching(null);
    setAiCoaching(null);
    loadData();
    return () => Object.values(controllers).forEach((controller) => controller.abort());
  }, [loadData]);

  return {
    aiCoaching,
    aiCoachingError,
    aiCoachingLoading,
    chartData,
    coaching,
    coachingError,
    coachingLoading,
    error,
    fileName,
    generateAiCoaching,
    loadCoaching,
    loadData,
    loadTimeline,
    loading,
    sections,
    timelineError,
    timelineLoading,
  };
}
