import { describe, expect, it } from "vitest";

import { presentationChatSessionKey, presentationContextDigest } from "./presentationChatSession";

describe("presentationChatSession", () => {
  it("분석 문맥 원문을 세션 키에 포함하지 않는 짧은 digest로 변환한다", () => {
    const context = "분석 상세 결과와 예상 질문을 포함한 긴 발표 문맥".repeat(20);
    const digest = presentationContextDigest(context);
    const key = presentationChatSessionKey(7, `context:${digest}`);

    expect(digest.length).toBeLessThan(24);
    expect(key).not.toContain(context);
    expect(key).toContain("speakinsight:chat-context:user:7:context:");
  });
});
