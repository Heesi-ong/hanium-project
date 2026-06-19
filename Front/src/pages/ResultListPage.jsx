// 사용자의 분석 이력 목록, 페이지네이션, 결과 삭제 흐름을 제공하는 페이지다.
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import StateMessage from "../components/StateMessage";
import ActionDialog from "../components/ActionDialog";
import Button from "../components/ui/Button";
import Card from "../components/ui/Card";
import { getScoreClassName } from "../features/analysis/formatters";
import ResultCard from "../features/analysis/ResultCard";
import useResultListData, { RESULT_PAGE_SIZE } from "../features/analysis/useResultListData";

import "./ResultListPage.css";

function ResultListPage() {
  const navigate = useNavigate();
  const [deleteTargetId, setDeleteTargetId] = useState("");
  const {
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
  } = useResultListData();

  const completedCount = summary.completed || 0;
  const failedCount = (summary.failed || 0) + (summary.cancelled || 0);
  const averageScore = summary.average_score ?? null;

  const moveToDetail = (resultId) => {
    navigate(`/result/${resultId}`);
  };

  const moveToUpload = () => {
    navigate("/upload");
  };

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

          <Button onClick={loadResults} disabled={loading}>
            {loading ? "불러오는 중..." : "새로고침"}
          </Button>
        </div>

        <Card>
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
        </Card>

        <Card>
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
                <Button
                  variant="secondary"
                  className="result-search-reset-button"
                  onClick={() => setSearchKeyword("")}
                >
                  검색어 초기화
                </Button>
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
            <Button
              aria-pressed={filter === "ALL"}
              variant={filter === "ALL" ? "primary" : "secondary"}
              onClick={() => setFilter("ALL")}
            >
              전체
            </Button>

            <Button
              aria-pressed={filter === "COMPLETED"}
              variant={filter === "COMPLETED" ? "primary" : "secondary"}
              onClick={() => setFilter("COMPLETED")}
            >
              성공
            </Button>

            <Button
              aria-pressed={filter === "FAILED"}
              variant={filter === "FAILED" ? "danger" : "secondary"}
              onClick={() => setFilter("FAILED")}
            >
              실패
            </Button>
            <Button
              aria-pressed={filter === "CANCELLED"}
              variant={filter === "CANCELLED" ? "danger" : "secondary"}
              onClick={() => setFilter("CANCELLED")}
            >
              취소
            </Button>
          </div>

          <p className="dashboard-subtitle">현재 표시 중: {results.length}건</p>
        </Card>

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

        {results.length === 0 && !loading && (
          <StateMessage
            type="empty"
            title="표시할 분석 결과가 없습니다"
            actions={
              <>
                <Button onClick={moveToUpload}>영상 업로드하기</Button>

                <Button variant="secondary" onClick={resetFilters}>
                  검색/필터 초기화
                </Button>
              </>
            }
          >
            아직 분석 결과가 없거나 현재 검색어와 필터 조건에 맞는 결과가 없습니다.
          </StateMessage>
        )}

        <div className="result-list-grid">
          {results.map((item) => (
            <ResultCard
              actionId={actionId}
              deletingId={deletingId}
              item={item}
              key={item.result_id}
              onDelete={setDeleteTargetId}
              onDetail={moveToDetail}
              onJobAction={runJobAction}
            />
          ))}
        </div>
        {total > RESULT_PAGE_SIZE && (
          <div className="result-pagination">
            <Button
              variant="secondary"
              disabled={page === 1}
              onClick={() => setPage((value) => value - 1)}
            >
              이전
            </Button>
            <span>
              {page} / {Math.ceil(total / RESULT_PAGE_SIZE)}
            </span>
            <Button
              variant="secondary"
              disabled={cursorSort ? !nextCursor : page >= Math.ceil(total / RESULT_PAGE_SIZE)}
              onClick={nextPage}
            >
              다음
            </Button>
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
          deleteResult(targetId);
        }}
      />
    </>
  );
}

export default ResultListPage;
