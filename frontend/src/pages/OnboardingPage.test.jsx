import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import OnboardingPage from "./OnboardingPage";

const onboardingApiMock = vi.hoisted(() => ({
    completeOnboarding: vi.fn(),
    skipOnboarding: vi.fn(),
}));

const authMock = vi.hoisted(() => ({
    user: null,
    updateUser: vi.fn(),
}));

vi.mock("../api/onboardingApi", () => ({
    completeOnboarding: onboardingApiMock.completeOnboarding,
    skipOnboarding: onboardingApiMock.skipOnboarding,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        user: authMock.user,
        updateUser: authMock.updateUser,
    }),
}));

function renderOnboardingPage(initialEntry = "/onboarding") {
    return render(
        <MemoryRouter initialEntries={[initialEntry]}>
            <Routes>
                <Route path="/onboarding" element={<OnboardingPage />} />
                <Route path="/" element={<div>홈</div>} />
                <Route path="/account" element={<div>계정</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("OnboardingPage", () => {
    beforeEach(() => {
        onboardingApiMock.completeOnboarding.mockReset();
        onboardingApiMock.skipOnboarding.mockReset();
        authMock.user = null;
        authMock.updateUser.mockReset();
    });

    it("saves onboarding answers and navigates to the redirect target", async () => {
        onboardingApiMock.completeOnboarding.mockResolvedValue({ success: true });

        renderOnboardingPage();

        expect(screen.getByRole("heading", { level: 1, name: "첫 분석 전에 코칭 방향을 맞춰볼게요." })).toBeInTheDocument();
        expect(screen.getByRole("form", { name: "발표 코칭 설정" })).toBeInTheDocument();
        expect(screen.getByRole("combobox", { name: "주로 어떤 목적으로 사용하시나요?" })).toHaveValue("INTERVIEW");
        expect(screen.getByRole("combobox", { name: "발표 경험 수준은 어느 정도인가요?" })).toHaveValue("BEGINNER");
        expect(screen.getByRole("combobox", { name: "가장 개선하고 싶은 부분은 무엇인가요?" })).toHaveValue("VOICE_TONE");

        fireEvent.click(screen.getByRole("button", { name: "저장하고 시작하기" }));

        await waitFor(() => {
            expect(onboardingApiMock.completeOnboarding).toHaveBeenCalledWith({
                purpose: "INTERVIEW",
                experienceLevel: "BEGINNER",
                improvementGoal: "VOICE_TONE",
            });
            expect(authMock.updateUser).toHaveBeenCalledWith({
                onboardingCompleted: true,
                onboardingSkipped: false,
                purpose: "INTERVIEW",
                experienceLevel: "BEGINNER",
                improvementGoal: "VOICE_TONE",
            });
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
    });

    // 2026-08-06 이전에는 이 버튼이 화면만 넘기고 서버에 아무것도 남기지 않아 다음
    // 로그인 때마다 다시 온보딩으로 보내졌다(P1-02). 이제는 skip 자체를 서버에 기록한다.
    it("persists the skip on the server before navigating to the redirect target", async () => {
        onboardingApiMock.skipOnboarding.mockResolvedValue({ success: true });

        renderOnboardingPage();

        fireEvent.click(screen.getByRole("button", { name: "나중에 하기" }));

        await waitFor(() => {
            expect(onboardingApiMock.skipOnboarding).toHaveBeenCalled();
            expect(authMock.updateUser).toHaveBeenCalledWith({ onboardingSkipped: true });
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
        expect(onboardingApiMock.completeOnboarding).not.toHaveBeenCalled();
    });

    it("cancels completed onboarding editing without recording a skip", async () => {
        authMock.user = {
            onboardingCompleted: true,
            onboardingSkipped: false,
            purpose: "LECTURE",
            experienceLevel: "ADVANCED",
            improvementGoal: "POSTURE",
        };

        renderOnboardingPage({
            pathname: "/onboarding",
            state: { from: "/account" },
        });

        expect(screen.getByRole("heading", { level: 1, name: "코칭 기준을 다시 맞춰볼까요?" })).toBeInTheDocument();
        expect(screen.getByRole("combobox", { name: "주로 어떤 목적으로 사용하시나요?" })).toHaveValue("LECTURE");
        expect(screen.getByRole("combobox", { name: "발표 경험 수준은 어느 정도인가요?" })).toHaveValue("ADVANCED");
        expect(screen.getByRole("combobox", { name: "가장 개선하고 싶은 부분은 무엇인가요?" })).toHaveValue("POSTURE");

        fireEvent.click(screen.getByRole("button", { name: "취소" }));

        await waitFor(() => {
            expect(screen.getByText("계정")).toBeInTheDocument();
        });
        expect(onboardingApiMock.skipOnboarding).not.toHaveBeenCalled();
        expect(authMock.updateUser).not.toHaveBeenCalled();
    });

    it("disables every action and field while saving", async () => {
        let resolveRequest;
        onboardingApiMock.completeOnboarding.mockImplementation(() => new Promise((resolve) => {
            resolveRequest = resolve;
        }));

        renderOnboardingPage();
        fireEvent.click(screen.getByRole("button", { name: "저장하고 시작하기" }));

        await waitFor(() => {
            expect(screen.getByRole("button", { name: "저장 중..." })).toBeDisabled();
            expect(screen.getByRole("button", { name: "나중에 하기" })).toBeDisabled();
            expect(screen.getByRole("combobox", { name: "주로 어떤 목적으로 사용하시나요?" })).toBeDisabled();
        });

        resolveRequest({ success: true });

        await waitFor(() => {
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
    });

    it("announces save errors without leaving the page", async () => {
        onboardingApiMock.completeOnboarding.mockRejectedValue(new Error("설정을 저장할 수 없습니다."));

        renderOnboardingPage();
        fireEvent.click(screen.getByRole("button", { name: "저장하고 시작하기" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("설정을 저장할 수 없습니다.");
        expect(screen.getByRole("form", { name: "발표 코칭 설정" })).toBeInTheDocument();
    });
});
