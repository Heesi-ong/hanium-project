import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getConversations, getMessages, getModels, getUsageSummary } from "../api/chatApi";
import ChatPage from "./ChatPage";

vi.mock("../api/chatApi", () => ({
  archiveConversation: vi.fn(),
  createConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getConversations: vi.fn(),
  getMessages: vi.fn(),
  getModels: vi.fn(),
  getUsageSummary: vi.fn(),
  renameConversation: vi.fn(),
  sendChat: vi.fn(),
}));

describe("ChatPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getModels.mockResolvedValue({ models: [{ provider: "ollama", displayName: "Ollama" }] });
    getUsageSummary.mockResolvedValue({ usage: { totalTokens: 0, estimatedCost: 0 } });
    getMessages.mockResolvedValue({ messages: [], total: 0 });
    getConversations.mockImplementation((_limit, _offset, _archived, cursor) =>
      Promise.resolve({
        conversations: cursor
          ? [{ id: 2, title: "두 번째 페이지", modelName: "Ollama" }]
          : [{ id: 1, title: "첫 대화", modelName: "Ollama" }],
        total: 11,
        next_cursor: cursor ? null : "cursor-2",
      }),
    );
  });

  it("서버 cursor로 다음 대화 페이지를 요청한다", async () => {
    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );

    const nextButton = await screen.findByRole("button", { name: "다음" });
    fireEvent.click(nextButton);

    await waitFor(() =>
      expect(getConversations).toHaveBeenCalledWith(10, 0, false, "cursor-2", expect.any(Object)),
    );
  });
});
