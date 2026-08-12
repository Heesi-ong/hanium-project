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

        fireEvent.click(screen.getByRole("button", { name: "취소" }));

        await waitFor(() => {
            expect(screen.getByText("계정")).toBeInTheDocument();
        });
        expect(onboardingApiMock.skipOnboarding).not.toHaveBeenCalled();
        expect(authMock.updateUser).not.toHaveBeenCalled();
    });
});
