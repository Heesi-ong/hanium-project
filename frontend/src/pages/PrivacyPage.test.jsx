import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import PrivacyPage from "./PrivacyPage";

function renderPrivacyPage() {
    return render(
        <MemoryRouter>
            <PrivacyPage />
        </MemoryRouter>
    );
}

describe("PrivacyPage", () => {
    it("identifies the project as a local controlled test", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "테스트 데이터 처리 안내" }))
            .toBeInTheDocument();
        expect(screen.getByText(/공개 온라인 서비스가 아닌 로컬\/통제된 테스트/))
            .toBeInTheDocument();
        expect(screen.queryByText(/실제 사업자/)).not.toBeInTheDocument();
    });

    it("requires consent and discloses optional external AI transfer", () => {
        renderPrivacyPage();

        expect(screen.getByText(/명시적 동의를 받은 경우에만/)).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "외부 AI 호출" })).toBeInTheDocument();
        expect(screen.getByText(/OpenAI API/)).toBeInTheDocument();
        expect(screen.getByText(/NVIDIA API/)).toBeInTheDocument();
    });

    it("explains deletion and opt-in backup behavior", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "보관과 삭제" })).toBeInTheDocument();
        expect(screen.getByText(/분석 완료 후 기본 30일/)).toBeInTheDocument();
        expect(screen.getByText(/기본 `docker compose up`은 자동 백업을 실행하지 않습니다/))
            .toBeInTheDocument();
    });

    it("links to the project usage guide", () => {
        renderPrivacyPage();

        expect(screen.getByRole("link", { name: "프로젝트 이용 안내 보기" }))
            .toHaveAttribute("href", "/terms");
    });
});
