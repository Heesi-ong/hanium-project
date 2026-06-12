import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import StateMessage from "../components/StateMessage";
import ActionDialog from "../components/ActionDialog";

import {
  cancelAnalyzeJob,
  getAnalyzeResults,
  deleteAnalyzeResult,
  retryAnalyzeJob,
} from "../api/analyzeApi";

import "./ResultListPage.css";

function ResultListPage() {
  const navigate = useNavigate();

  const [results, setResults] = useState([]);
  const [filter, setFilter] = useState("ALL");
  const [sortType, setSortType] = useState("LATEST");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [loading, setLoading] = useState(false);
  const [deletingId, setDeletingId] = useState("");
  const [actionId, setActionId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState({});
  const [deleteTargetId, setDeleteTargetId] = useState("");
  const [cursorPages, setCursorPages] = useState([null]);
  const [nextCursor, setNextCursor] = useState(null);
  const requestController = useRef(null);
  const pageSize = 12;
  const cursorSort = sortType === "LATEST" || sortType === "OLDEST";
  const currentCursor = cursorPages[page - 1] || null;

  const formatDateTime = (value) => {
    if (!value) return "-";

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString("ko-KR", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  const getScoreClassName = (score) => {
    if (typeof score !== "number") return "";
    if (score >= 80) return "score-good";
    if (score >= 60) return "score-normal";
    return "score-bad";
  };

  const getStatusClassName = (status) => {
    if (status === "COMPLETED") return "status-badge status-completed";
    if (status === "FAILED") return "status-badge status-failed";
    if (status === "CANCELLED") return "status-badge status-cancelled";
    return "status-badge";
  };

  const getStatusText = (status) => {
    if (status === "COMPLETED") return "분석 완료";
    if (status === "FAILED") return "분석 실패";
    if (status === "CANCELLED") return "분석 취소";
    if (status === "QUEUED") return "대기 중";
    return "처리 중";
  };

  const getStatusIcon = (status) => {
    if (status === "COMPLETED") return "🟢";
    if (status === "FAILED") return "🔴";
    if (status === "CANCELLED") return "⚪";
    return "🔵";
  };

  const getResultCardClassName = (status) => {
    if (status === "COMPLETED") {
      return "card result-list-card result-card-completed";
    }

    if (status === "FAILED") {
      return "card result-list-card result-card-failed";
    }
    if (status === "CANCELLED") {
      return "card result-list-card result-card-cancelled";
    }

    return "card result-list-card result-card-processing";
  };

  const completedCount = summary.completed || 0;
  const failedCount = (summary.failed || 0) + (summary.cancelled || 0);
  const averageScore = summary.average_score ?? null;

  const searchedResults = results.filter((item) => {
    const keyword = searchKeyword.trim().toLowerCase();

    if (!keyword) return true;

    const filename = item.original_filename?.toLowerCase() || "";
    const feedback = item.summary_feedback?.toLowerCase() || "";
    const createdAt = item.created_at?.toLowerCase() || "";
    const formattedCreatedAt = formatDateTime(item.created_at).toLowerCase();

    return (
      filename.includes(keyword) ||
      feedback.includes(keyword) ||
      createdAt.includes(keyword) ||
      formattedCreatedAt.includes(keyword)
    );
  });

  const filteredResults = searchedResults.filter((item) => {
    if (filter === "ALL") return true;
    return item.status === filter;
  });

  const sortedResults = [...filteredResults].sort((a, b) => {
    if (sortType === "LATEST") {
      return new Date(b.created_at) - new Date(a.created_at);
    }

    if (sortType === "OLDEST") {
      return new Date(a.created_at) - new Date(b.created_at);
    }

    if (sortType === "SCORE_HIGH") {
      if (a.total_score === null) return 1;
      if (b.total_score === null) return -1;
      return b.total_score - a.total_score;
    }

    if (sortType === "SCORE_LOW") {
      if (a.total_score === null) return 1;
      if (b.total_score === null) return -1;
      return a.total_score - b.total_score;
    }

    return 0;
  });

  const loadResults = async () => {
    try {
      setLoading(true);
      setError("");
      setNotice("");
      requestController.current?.abort();
      requestController.current = new AbortController();

      const response = await getAnalyzeResults(
        {
          status: filter === "ALL" ? "" : filter,
          search: searchKeyword.trim(),
          sort: sortType.toLowerCase(),
          limit: pageSize,
          offset: cursorSort ? 0 : (page - 1) * pageSize,
          cursor: cursorSort ? currentCursor : "",
        },
        requestController.current.signal,
      );
      setResults(response.results || []);
      setTotal(response.total || 0);
      setSummary(response.summary || {});
      setNextCursor(response.next_cursor || null);
    } catch (err) {
      if (err.name === "AbortError") return;
      console.error(err);
      setError(err.message || "분석 이력을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  };

  const resetSearchKeyword = () => {
    setSearchKeyword("");
  };

  const resetFilters = () => {
    setSearchKeyword("");
    setFilter("ALL");
    setSortType("LATEST");
  };

  const handleDelete = async (resultId) => {
    try {
      setDeletingId(resultId);
      setError("");
      setNotice("");

      const response = await deleteAnalyzeResult(resultId);

      if (!response?.deleted_job) {
        throw new Error("분석 작업을 삭제하지 못했습니다.");
      }

      await loadResults();
      setNotice("분석 결과가 삭제되었습니다.");
    } catch (err) {
      console.error(err);
      setError(err.message || "분석 결과 삭제에 실패했습니다.");
    } finally {
      setDeletingId("");
    }
  };

  const handleJobAction = async (resultId, action) => {
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
    } catch (err) {
      setError(err.message || "분석 작업 상태를 변경하지 못했습니다.");
    } finally {
      setActionId("");
    }
  };

  const moveToDetail = (resultId) => {
    navigate(`/result/${resultId}`);
  };

  const moveToUpload = () => {
    navigate("/upload");
  };

  useEffect(() => {
    setPage(1);
    setCursorPages([null]);
  }, [filter, sortType, searchKeyword]);

  useEffect(() => {
    loadResults();
    return () => requestController.current?.abort();
  }, [filter, sortType, searchKeyword, page, currentCursor]);

  useEffect(() => {
    if (!results.some((item) => item.status === "QUEUED" || item.status === "PROCESSING")) {
      return undefined;
    }
    const intervalId = window.setInterval(() => {
      if (!document.hidden) loadResults();
    }, 3000);
    return () => window.clearInterval(intervalId);
  }, [results]);

  return (
    <>
      <div className="page">
        <div className="dashboard-header">
          <div>
            <h1 className="page-title">분석 이력</h1>
            <p className="dashboard-subtitle">
              업로드한 발표 영상의 분석 결과를 한눈에 확인합니다.
            </p>
          </div>

          <button className="button" onClick={loadResults} disabled={loading}>
            {loading ? "불러오는 중..." : "새로고침"}
          </button>
        </div>

        <div className="card">
          <div className="metric-grid">
            <div className="metric-item">
              <div className="metric-label">총 분석 건수</div>
              <div className="metric-value">{summary.total || 0}건</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">성공</div>
              <div className="metric-value">{completedCount}건</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">실패</div>
              <div className="metric-value">{failedCount}건</div>
            </div>

            <div className="metric-item">
              <div className="metric-label">평균 점수</div>
              <div className={`metric-value ${getScoreClassName(averageScore)}`}>
                {averageScore === null ? "측정 불가" : `${averageScore}점`}
              </div>
            </div>
          </div>
        </div>

        <div className="card">
          <h2>결과 검색 및 필터</h2>

          <div className="result-control-grid">
            <div className="result-search-area">
              <label className="metric-label" htmlFor="result-search">
                검색어
              </label>

              <input
                id="result-search"
                className="result-search-input"
                type="text"
                value={searchKeyword}
                onChange={(event) => setSearchKeyword(event.target.value)}
                placeholder="파일명, 피드백, 생성일 검색"
              />

              {searchKeyword && (
                <button
                  className="button secondary result-search-reset-button"
                  onClick={resetSearchKeyword}
                >
                  검색어 초기화
                </button>
              )}
            </div>

            <div className="result-sort-area">
              <label className="metric-label" htmlFor="result-sort">
                정렬 기준
              </label>

              <select
                id="result-sort"
                className="result-sort-select"
                value={sortType}
                onChange={(event) => setSortType(event.target.value)}
              >
                <option value="LATEST">최신순</option>
                <option value="OLDEST">오래된순</option>
                <option value="SCORE_HIGH">점수 높은순</option>
                <option value="SCORE_LOW">점수 낮은순</option>
              </select>
            </div>
          </div>

          <div className="filter-button-group">
            <button
              className={filter === "ALL" ? "button" : "button secondary"}
              onClick={() => setFilter("ALL")}
            >
              전체
            </button>

            <button
              className={filter === "COMPLETED" ? "button" : "button secondary"}
              onClick={() => setFilter("COMPLETED")}
            >
              성공
            </button>

            <button
              className={filter === "FAILED" ? "button danger" : "button secondary"}
              onClick={() => setFilter("FAILED")}
            >
              실패
            </button>
            <button
              className={filter === "CANCELLED" ? "button danger" : "button secondary"}
              onClick={() => setFilter("CANCELLED")}
            >
              취소
            </button>
          </div>

          <p className="dashboard-subtitle">현재 표시 중: {sortedResults.length}건</p>
        </div>

        {loading && <StateMessage title="분석 이력을 불러오는 중입니다." />}

        {error && (
          <StateMessage type="error" title="분석 이력을 불러오지 못했습니다.">
            {error}
          </StateMessage>
        )}

        {notice && (
          <StateMessage type="success" compact>
            {notice}
          </StateMessage>
        )}

        {sortedResults.length === 0 && !loading && (
          <StateMessage
            type="empty"
            title="표시할 분석 결과가 없습니다"
            actions={
              <>
                <button className="button" onClick={moveToUpload}>
                  영상 업로드하기
                </button>

                <button className="button secondary" onClick={resetFilters}>
                  검색/필터 초기화
                </button>
              </>
            }
          >
            아직 분석 결과가 없거나 현재 검색어와 필터 조건에 맞는 결과가 없습니다.
          </StateMessage>
        )}

        <div className="result-list-grid">
          {sortedResults.map((item) => {
            const score = typeof item.total_score === "number" ? item.total_score : null;

            return (
              <div
                className={getResultCardClassName(item.status)}
                key={item.result_id}
                onClick={() => {
                  if (item.status === "COMPLETED") {
                    moveToDetail(item.result_id);
                  }
                }}
              >
                <div className="result-card-header">
                  <div className="result-card-title-area">
                    <div className="result-title-row">
                      <h2 className="result-filename" title={item.original_filename}>
                        <span className="result-status-icon">{getStatusIcon(item.status)}</span>
                        {item.original_filename || "파일명 없음"}
                      </h2>

                      <span className={getStatusClassName(item.status)}>
                        {getStatusText(item.status)}
                      </span>
                    </div>
                  </div>

                  {item.status === "COMPLETED" && (
                    <div
                      className={`mini-score-circle ${score === null ? "unavailable" : ""}`}
                      style={score === null ? undefined : { "--score": score }}
                    >
                      <div className="mini-score-circle-inner">
                        <div className={`mini-score-value ${getScoreClassName(score)}`}>
                          {score === null ? "N/A" : score}
                        </div>

                        <div className="mini-score-label">SCORE</div>
                      </div>
                    </div>
                  )}
                </div>

                <div className="metric-grid">
                  <div className="metric-item">
                    <div className="metric-label">총점</div>
                    <div className={`metric-value ${getScoreClassName(score)}`}>
                      {score === null ? "측정 불가" : score}
                    </div>
                  </div>

                  <div className="metric-item">
                    <div className="metric-label">처리 시간</div>
                    <div className="metric-value">{item.processing_time_seconds ?? "-"}초</div>
                  </div>
                </div>

                <p className="result-feedback">{item.summary_feedback ?? "-"}</p>

                {(item.status === "QUEUED" || item.status === "PROCESSING") && (
                  <div className="result-progress-area">
                    <div className="result-progress-heading">
                      <span>{item.stage || "queued"}</span>
                      <strong>{item.progress || 0}%</strong>
                    </div>
                    <div className="result-progress-track">
                      <div style={{ width: `${item.progress || 0}%` }} />
                    </div>
                  </div>
                )}

                {item.error && <p className="error-text">오류: {item.error}</p>}

                <div className="result-date-row">
                  <span className="result-date-label">생성일</span>
                  <span className="result-date-value">{formatDateTime(item.created_at)}</span>
                </div>

                <div className="result-action-row">
                  {item.status === "COMPLETED" && (
                    <button
                      className="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        moveToDetail(item.result_id);
                      }}
                    >
                      상세 보기
                    </button>
                  )}

                  {(item.status === "QUEUED" || item.status === "PROCESSING") && (
                    <button
                      className="button secondary"
                      disabled={actionId === item.result_id}
                      onClick={(event) => {
                        event.stopPropagation();
                        handleJobAction(item.result_id, "cancel");
                      }}
                    >
                      {actionId === item.result_id ? "처리 중..." : "취소"}
                    </button>
                  )}

                  {(item.status === "FAILED" || item.status === "CANCELLED") &&
                    item.retry_available && (
                      <button
                        className="button secondary"
                        disabled={
                          actionId === item.result_id || item.attempt_count >= item.max_attempts
                        }
                        onClick={(event) => {
                          event.stopPropagation();
                          handleJobAction(item.result_id, "retry");
                        }}
                      >
                        {actionId === item.result_id ? "처리 중..." : "재시도"}
                      </button>
                    )}

                  <button
                    className="button danger"
                    disabled={
                      deletingId === item.result_id ||
                      item.status === "QUEUED" ||
                      item.status === "PROCESSING"
                    }
                    onClick={(event) => {
                      event.stopPropagation();
                      setDeleteTargetId(item.result_id);
                    }}
                  >
                    {deletingId === item.result_id ? "삭제 중..." : "삭제"}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
        {total > pageSize && (
          <div className="result-pagination">
            <button
              className="button secondary"
              disabled={page === 1}
              onClick={() => setPage((value) => value - 1)}
            >
              이전
            </button>
            <span>
              {page} / {Math.ceil(total / pageSize)}
            </span>
            <button
              className="button secondary"
              disabled={cursorSort ? !nextCursor : page >= Math.ceil(total / pageSize)}
              onClick={() => {
                if (cursorSort) {
                  setCursorPages((current) => {
                    const next = [...current];
                    next[page] = nextCursor;
                    return next;
                  });
                }
                setPage((value) => value + 1);
              }}
            >
              다음
            </button>
          </div>
        )}
      </div>
      <ActionDialog
        open={Boolean(deleteTargetId)}
        title="이 분석 결과를 삭제하시겠습니까?"
        description="삭제한 분석 결과는 복구할 수 없습니다."
        confirmLabel="결과 삭제"
        danger
        onCancel={() => setDeleteTargetId("")}
        onConfirm={() => {
          const targetId = deleteTargetId;
          setDeleteTargetId("");
          handleDelete(targetId);
        }}
      />
    </>
  );
}

export default ResultListPage;
