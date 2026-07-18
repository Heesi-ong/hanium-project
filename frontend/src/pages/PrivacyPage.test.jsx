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
    it("renders business operator placeholders", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "사업자 정보" }))
            .toBeInTheDocument();
        expect(screen.getByText("상호: [실제 서비스명/사업자명 입력 필요]"))
            .toBeInTheDocument();
        expect(screen.getByText("사업자등록번호: [실제 사업자등록번호 입력 필요]"))
            .toBeInTheDocument();
    });

    it("renders privacy officer placeholders", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "개인정보 보호책임자" }))
            .toBeInTheDocument();
        expect(screen.getByText(/실제 개인정보 보호책임자 이름 또는 직책 입력 필요/))
            .toBeInTheDocument();
        expect(screen.getByText("전화: [실제 개인정보 문의 전화번호 입력 필요]"))
            .toBeInTheDocument();
    });

    it("renders cross-border transfer notice for OpenAI and NVIDIA", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "국외 이전에 관한 사항" }))
            .toBeInTheDocument();
        expect(screen.getByText(/OpenAI Responses API 호출/))
            .toBeInTheDocument();
        expect(screen.getByText(/NVCF Asset API 업로드/))
            .toBeInTheDocument();
    });

    it("discloses backup retention period and encryption", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "백업 보관" }))
            .toBeInTheDocument();
        expect(screen.getByText(/BACKUP_RETENTION_DAYS/))
            .toBeInTheDocument();
        expect(screen.getByText(/AES-256으로/))
            .toBeInTheDocument();
    });

    it("discloses backend and analysis engine log retention periods", () => {
        renderPrivacyPage();

        expect(screen.getByRole("heading", { name: "로그 보관" }))
            .toBeInTheDocument();
        expect(screen.getByText(/최대 30일 또는 총 1GB/))
            .toBeInTheDocument();
        expect(screen.getByText(/파일 로그도 일 단위로 순환되며 최대 30일 동안 보관/))
            .toBeInTheDocument();
    });
});
