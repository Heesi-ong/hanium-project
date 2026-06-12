import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  createConversation,
  getConversations,
  getMessages,
  getModels,
  getUsageSummary,
  sendChat,
} from "../api/chatApi";
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

  it("예상 질문은 표시하지만 사용자 답변 입력창은 비워 둔다", async () => {
    createConversation.mockResolvedValue({ conversation: { id: 1 } });
    const view = render(
      <MemoryRouter
        initialEntries={[
          {
            pathname: "/chat",
            state: {
              analysisContext: "분석 결과",
              practiceQuestion: "프로젝트의 핵심 가치는 무엇인가요?",
            },
          },
        ]}
      >
        <ChatPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("프로젝트의 핵심 가치는 무엇인가요?")).toBeInTheDocument();
    expect(view.container.querySelector('textarea[placeholder="메시지를 입력하세요"]')).toHaveValue(
      "",
    );
    expect(createConversation).toHaveBeenCalledWith(
      expect.objectContaining({
        system_prompt: expect.stringContaining("명확성, 근거, 간결성, 설득력"),
      }),
    );
  });

  it("분석 결과 ID로 발표 코칭 대화를 만들고 사용자의 답변을 전송한다", async () => {
    createConversation.mockResolvedValue({ conversation: { id: 1 } });
    sendChat.mockResolvedValue({
      userMessage: {
        id: 10,
        role: "user",
        content: "제가 검증한 결과는 응답 시간이 줄어든 것입니다.",
      },
      assistantMessage: { id: 11, role: "assistant", content: "근거 수치를 덧붙이세요." },
      model: { displayName: "Ollama" },
    });
    render(
      <MemoryRouter
        initialEntries={[
          {
            pathname: "/chat",
            state: {
              analysisResultId: "job-1",
              analysisTitle: "발표 코칭",
              practiceQuestion: "검증 결과는 무엇인가요?",
            },
          },
        ]}
      >
        <ChatPage />
      </MemoryRouter>,
    );

    const textarea = await screen.findByPlaceholderText("메시지를 입력하세요");
    fireEvent.change(textarea, {
      target: { value: "제가 검증한 결과는 응답 시간이 줄어든 것입니다." },
    });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() =>
      expect(createConversation).toHaveBeenCalledWith(
        expect.objectContaining({
          analysis_result_id: "job-1",
          practice_question: "검증 결과는 무엇인가요?",
          system_prompt: undefined,
        }),
      ),
    );
    await waitFor(() =>
      expect(sendChat).toHaveBeenCalledWith(
        1,
        "제가 검증한 결과는 응답 시간이 줄어든 것입니다.",
        expect.any(Object),
      ),
    );
  });
});
