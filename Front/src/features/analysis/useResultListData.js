// 분석 이력 목록의 조회, 페이지네이션, 취소, 재시도, 삭제 상태를 관리한다.
import { useCallback, useEffect, useRef, useState } from "react";

import {
  cancelAnalyzeJob,
  deleteAnalyzeResult,
  getAnalyzeResults,
  retryAnalyzeJob,
} from "../../api/analyzeApi";

export const RESULT_PAGE_SIZE = 12;

export default function useResultListData() {
  const [results, setResults] = useState([]);
  const [filter, setFilter] = useState("ALL");
  const [sortType, setSortType] = useState("LATEST");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [debouncedSearchKeyword, setDebouncedSearchKeyword] = useState("");
  const [loading, setLoading] = useState(false);
  const [deletingId, setDeletingId] = useState("");
  const [actionId, setActionId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState({});
  const [cursorPages, setCursorPages] = useState([null]);
  const [nextCursor, setNextCursor] = useState(null);
  const requestController = useRef(null);
  const cursorSort = sortType === "LATEST" || sortType === "OLDEST";
  const currentCursor = cursorPages[page - 1] || null;

  const loadResults = useCallback(async () => {
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    try {
      setLoading(true);
      setError("");
      setNotice("");
      const response = await getAnalyzeResults(
        {
          status: filter === "ALL" ? "" : filter,
          search: debouncedSearchKeyword.trim(),
          sort: sortType.toLowerCase(),
          limit: RESULT_PAGE_SIZE,
          offset: cursorSort ? 0 : (page - 1) * RESULT_PAGE_SIZE,
          cursor: cursorSort ? currentCursor : "",
        },
        controller.signal,
      );
      if (requestController.current !== controller) return;
      setResults(response.results || []);
      setTotal(response.total || 0);
      setSummary(response.summary || {});
      setNextCursor(response.next_cursor || null);
    } catch (requestError) {
      if (requestError.name === "AbortError" || requestController.current !== controller) return;
      setError(requestError.message || "분석 이력을 불러오지 못했습니다.");
    } finally {
      if (requestController.current === controller) setLoading(false);
    }
  }, [currentCursor, cursorSort, debouncedSearchKeyword, filter, page, sortType]);

  const deleteResult = async (resultId) => {
    try {
      setDeletingId(resultId);
      setError("");
      setNotice("");
      const response = await deleteAnalyzeResult(resultId);
      if (!response?.deleted_job) throw new Error("분석 작업을 삭제하지 못했습니다.");
      await loadResults();
      setNotice("분석 결과가 삭제되었습니다.");
    } catch (requestError) {
      setError(requestError.message || "분석 결과 삭제에 실패했습니다.");
    } finally {
      setDeletingId("");
    }
  };

  const runJobAction = async (resultId, action) => {
    try {
      setActionId(resultId);
      setError("");
      setNotice("");
      if (action === "cancel") {
        await cancelAnalyzeJob(resultId);
        setNotice("분석 취소를 요청했습니다.");
      } else {
        await retryAnalyzeJob(resultId);
        setNotice("분석 작업을 다시 대기열에 등록했습니다.");
      }
      await loadResults();
    } catch (requestError) {
      setError(requestError.message || "분석 작업 상태를 변경하지 못했습니다.");
    } finally {
      setActionId("");
    }
  };

  const resetFilters = () => {
    setSearchKeyword("");
    setFilter("ALL");
    setSortType("LATEST");
  };

  const nextPage = () => {
    if (cursorSort) {
      setCursorPages((current) => {
        const next = [...current];
        next[page] = nextCursor;
        return next;
      });
    }
    setPage((value) => value + 1);
  };

  useEffect(() => {
    setPage(1);
    setCursorPages([null]);
  }, [filter, sortType, debouncedSearchKeyword]);

  useEffect(() => {
    const timerId = window.setTimeout(() => setDebouncedSearchKeyword(searchKeyword), 300);
    return () => window.clearTimeout(timerId);
  }, [searchKeyword]);

  useEffect(() => {
    void loadResults();
    return () => requestController.current?.abort();
  }, [loadResults]);

  useEffect(() => {
    if (!results.some((item) => item.status === "QUEUED" || item.status === "PROCESSING")) {
      return undefined;
    }
    const intervalId = window.setInterval(() => {
      if (!document.hidden) void loadResults();
    }, 3000);
    return () => window.clearInterval(intervalId);
  }, [loadResults, results]);

  return {
    actionId,
    cursorSort,
    deletingId,
    deleteResult,
    error,
    filter,
    loadResults,
    loading,
    nextCursor,
    nextPage,
    notice,
    page,
    resetFilters,
    results,
    runJobAction,
    searchKeyword,
    setFilter,
    setPage,
    setSearchKeyword,
    setSortType,
    sortType,
    summary,
    total,
  };
}
