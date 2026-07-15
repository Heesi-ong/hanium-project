import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import OnboardingPage from "./OnboardingPage";

const onboardingApiMock = vi.hoisted(() => ({
    completeOnboarding: vi.fn(),
}));

const authMock = vi.hoisted(() => ({
    updateUser: vi.fn(),
}));

vi.mock("../api/onboardingApi", () => ({
    completeOnboarding: onboardingApiMock.completeOnboarding,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        updateUser: authMock.updateUser,
    }),
}));

function renderOnboardingPage() {
    return render(
        <MemoryRouter initialEntries={["/onboarding"]}>
            <Routes>
                <Route path="/onboarding" element={<OnboardingPage />} />
                <Route path="/" element={<div>홈</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("OnboardingPage", () => {
    beforeEach(() => {
        onboardingApiMock.completeOnboarding.mockReset();
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
            expect(authMock.updateUser).toHaveBeenCalledWith({ onboardingCompleted: true });
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
    });

    it("skips onboarding without calling the API", async () => {
        renderOnboardingPage();

        fireEvent.click(screen.getByRole("button", { name: "나중에 하기" }));

        await waitFor(() => {
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
        expect(onboardingApiMock.completeOnboarding).not.toHaveBeenCalled();
    });
});
