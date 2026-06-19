import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { changePassword, deleteAccount, exportUserData, getStorageUsage } from "../api/accountApi";
import AccountPage from "./AccountPage";

vi.mock("../api/accountApi", () => ({
  changePassword: vi.fn(),
  deleteAccount: vi.fn(),
  exportUserData: vi.fn(),
  getStorageUsage: vi.fn(),
  logoutAll: vi.fn(),
  updateProfile: vi.fn(),
}));

const user = { id: 7, email: "user@example.com", displayName: "사용자", role: "user" };

describe("AccountPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    getStorageUsage.mockResolvedValue({
      storage: {
        used_bytes: 0,
        quota_bytes: 1024 * 1024,
        active_analysis_count: 0,
        max_active_analyses: 2,
      },
    });
  });

  it("현재 비밀번호와 새 비밀번호를 사용해 비밀번호를 변경한다", async () => {
    changePassword.mockResolvedValue(null);
    render(<AccountPage user={user} onUserChange={vi.fn()} onSignedOut={vi.fn()} />);

    fireEvent.change(screen.getByLabelText("현재 비밀번호"), {
      target: { value: "current-password" },
    });
    fireEvent.change(screen.getByLabelText("새 비밀번호"), { target: { value: "new-password" } });
    fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

    await waitFor(() =>
      expect(changePassword).toHaveBeenCalledWith({
        current_password: "current-password",
        new_password: "new-password",
      }),
    );
    expect(screen.getByLabelText("현재 비밀번호")).toHaveValue("");
    expect(screen.getByLabelText("새 비밀번호")).toHaveValue("");
  });

  it("확인 대화상자를 거쳐 계정을 삭제하고 로그아웃 상태로 전환한다", async () => {
    deleteAccount.mockResolvedValue(null);
    const onSignedOut = vi.fn();
    render(<AccountPage user={user} onUserChange={vi.fn()} onSignedOut={onSignedOut} />);

    fireEvent.change(screen.getByLabelText("탈퇴 확인 비밀번호"), {
      target: { value: "current-password" },
    });
    fireEvent.click(screen.getByRole("button", { name: "계정 탈퇴" }));
    fireEvent.click(screen.getByRole("button", { name: "계정 삭제" }));

    await waitFor(() =>
      expect(deleteAccount).toHaveBeenCalledWith({ password: "current-password" }),
    );
    expect(onSignedOut).toHaveBeenCalled();
  });

  it("사용자 데이터를 JSON 파일로 내보낸다", async () => {
    exportUserData.mockResolvedValue({ profile: user });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const createObjectURL = vi.fn(() => "blob:test");
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    render(<AccountPage user={user} onUserChange={vi.fn()} onSignedOut={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "내 데이터 내보내기" }));

    await waitFor(() => expect(exportUserData).toHaveBeenCalled());
    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:test");
    click.mockRestore();
  });
});
