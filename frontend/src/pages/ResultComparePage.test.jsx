import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import ResultComparePage from "./ResultComparePage";

vi.mock("../components/chart/ScoreCompareBarChart", () => ({
    default: () => <div data-testid="score-compare-bar-chart" />,
    SCORE_FIELDS: [
        { key: "totalScore", label: "총점" },
        { key: "postureScore", label: "자세" },
        { key: "gazeScore", label: "시선" },
        { key: "speechScore", label: "음성" },
        { key: "gestureScore", label: "제스처" },
        { key: "expressionScore", label: "표정" },
    ],
}));

function renderWithResults(results) {
    return render(
        <MemoryRouter
            initialEntries={[
                {
                    pathname: "/results/compare",
                    state: results ? { results } : undefined,
                },
            ]}
        >
            <ResultComparePage />
        </MemoryRouter>
    );
}

const resultA = {
    jobId: "job-a",
    fileName: "1차연습.mp4",
    createdAt: "2026-07-01T09:00:00",
    scoreSummary: {
        totalScore: 65,
        postureScore: 70,
        gazeScore: 60,
        speechScore: 55,
        gestureScore: 80,
        expressionScore: 50,
    },
    feedback: {
        generationMode: "REAL",
        model: "gpt-4.1-mini",
        realApiUsed: true,
        overall: "1차 피드백 내용입니다.",
    },
};

const resultB = {
    jobId: "job-b",
    fileName: "2차연습.mp4",
    createdAt: "2026-07-10T09:00:00",
    scoreSummary: {
        totalScore: 82,
        postureScore: 75,
        gazeScore: 88,
        speechScore: 60,
        gestureScore: 85,
        expressionScore: 90,
    },
    feedback: {
        generationMode: "REAL",
        model: "gpt-4.1-mini",
        realApiUsed: true,
        overall: "2차 피드백 내용입니다.",
    },
};

describe("ResultComparePage", () => {
    it("shows guidance and a link back to the result list when no results were passed via navigation state", () => {
        renderWithResults(undefined);

        expect(
            screen.getByText("비교할 결과 정보가 없습니다.")
        ).toBeInTheDocument();
        expect(
            screen.getByRole("link", { name: "분석 결과 목록으로 이동" })
        ).toHaveAttribute("href", "/results");
    });

    it("renders both results' titles, dates, and feedback side by side", () => {
        renderWithResults([resultA, resultB]);

        // 제목은 헤더 카드와 점수 비교 표 열 이름 양쪽에 나타나므로 개수만 확인합니다.
        expect(screen.getAllByText("1차연습.mp4").length).toBeGreaterThanOrEqual(1);
        expect(screen.getAllByText("2차연습.mp4").length).toBeGreaterThanOrEqual(1);
        expect(screen.getByText("1차 피드백 내용입니다.")).toBeInTheDocument();
        expect(screen.getByText("2차 피드백 내용입니다.")).toBeInTheDocument();
    });

    it("computes the score delta per category and marks improvement/decline correctly", () => {
        renderWithResults([resultA, resultB]);

        // 총점: 65 -> 82, +17 (개선)
        expect(screen.getByText("▲ +17")).toBeInTheDocument();
        // 시선: 60 -> 88, +28 (개선)
        expect(screen.getByText("▲ +28")).toBeInTheDocument();
        // 표정: 50 -> 90, +40 (개선)
        expect(screen.getByText("▲ +40")).toBeInTheDocument();
        // 자세(70->75)/음성(55->60)/제스처(80->85) 모두 +5로 동일 - 3건 존재를 확인
        expect(screen.getAllByText("▲ +5")).toHaveLength(3);
    });
});
