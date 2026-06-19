import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
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
  restoreConversation: vi.fn(),
  sendChat: vi.fn(),
}));

describe("ChatPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
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

  it("응답 중단 후 서버에 저장된 메시지를 다시 조회한다", async () => {
    getMessages.mockResolvedValueOnce({ messages: [], total: 0 }).mockResolvedValueOnce({
      messages: [{ id: 20, role: "assistant", content: "서버에 저장된 응답" }],
      total: 1,
    });
    sendChat.mockImplementation(
      (_conversationId, _question, signal) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener("abort", () =>
            reject(new DOMException("응답 표시를 중단했습니다.", "AbortError")),
          );
        }),
    );

    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );

    const textarea = await screen.findByPlaceholderText("메시지를 입력하세요");
    fireEvent.change(textarea, { target: { value: "중단 후 동기화 테스트" } });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));
    fireEvent.click(await screen.findByRole("button", { name: "응답 중단" }));

    expect(await screen.findByText("서버에 저장된 응답")).toBeInTheDocument();
    expect(getMessages).toHaveBeenCalledTimes(2);
  });

  it("사용량 조회가 실패해도 대화 목록과 채팅 입력을 표시한다", async () => {
    getUsageSummary.mockRejectedValue(new Error("사용량 오류"));

    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("button", { name: "첫 대화" })).toBeInTheDocument();
    expect(screen.getByLabelText("AI 코치에게 보낼 메시지")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "사용량 다시 불러오기" })).toBeInTheDocument();
  });

  it("모델 조회가 실패하면 기본 Ollama 표시와 채팅 기능을 유지한다", async () => {
    getModels.mockRejectedValue(new Error("모델 오류"));

    render(
      <MemoryRouter>
        <ChatPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("Ollama")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "첫 대화" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "모델 정보 다시 불러오기" })).toBeInTheDocument();
  });

  it("결과 기반 채팅 URL을 직접 열고 같은 세션에서 다시 열어도 대화를 중복 생성하지 않는다", async () => {
    createConversation.mockResolvedValue({ conversation: { id: 7 } });
    const renderResultChat = () =>
      render(
        <MemoryRouter initialEntries={["/chat/result/job-1?question=핵심%20가치는%20무엇인가요"]}>
          <Routes>
            <Route path="/chat/result/:resultId" element={<ChatPage />} />
          </Routes>
        </MemoryRouter>,
      );

    const first = renderResultChat();
    expect(await screen.findByText("핵심 가치는 무엇인가요")).toBeInTheDocument();
    await waitFor(() =>
      expect(createConversation).toHaveBeenCalledWith(
        expect.objectContaining({
          analysis_result_id: "job-1",
          practice_question: "핵심 가치는 무엇인가요",
        }),
      ),
    );
    first.unmount();

    renderResultChat();
    expect(await screen.findByText("핵심 가치는 무엇인가요")).toBeInTheDocument();
    expect(createConversation).toHaveBeenCalledTimes(1);
  });

  it("저장된 결과 대화가 삭제되었으면 세션 키를 제거하고 새 대화를 만든다", async () => {
    window.sessionStorage.setItem(
      "speakinsight:chat-context:user:7:result:job-1:question:질문",
      "99",
    );
    getMessages.mockRejectedValueOnce(
      Object.assign(new Error("대화를 찾을 수 없습니다."), { status: 404 }),
    );
    createConversation.mockResolvedValue({ conversation: { id: 8 } });

    render(
      <MemoryRouter initialEntries={["/chat/result/job-1?question=질문"]}>
        <Routes>
          <Route path="/chat/result/:resultId" element={<ChatPage user={{ id: 7 }} />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(createConversation).toHaveBeenCalledTimes(1));
    expect(
      window.sessionStorage.getItem("speakinsight:chat-context:user:7:result:job-1:question:질문"),
    ).toBe("8");
  });
});
