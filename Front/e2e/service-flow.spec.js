import { expect, test } from "@playwright/test";

const email = process.env.E2E_EMAIL || `speakinsight-e2e-${Date.now()}@example.com`;
const password = process.env.E2E_PASSWORD || "E2e-Test-Password-2026";
const videoPath = process.env.E2E_VIDEO_PATH;

test.describe("실제 서비스 흐름", () => {
  test.skip(!videoPath, "E2E_VIDEO_PATH가 필요합니다.");

  test.afterEach(async ({ page }) => {
    await page.request.delete("/api/auth/account", { data: { password }, failOnStatusCode: false });
  });

  test("회원가입부터 영상 분석, Ollama 코칭, 계정 삭제까지 완료한다", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "계정이 없나요? 회원가입" }).click();
    await page.getByLabel("표시 이름").fill("E2E 사용자");
    await page.getByLabel("이메일").fill(email);
    await page.getByLabel("비밀번호").fill(password);
    await page.getByRole("button", { name: "회원가입" }).click();

    await expect(page.getByRole("heading", { name: "발표 영상 분석" })).toBeVisible();
    await page.locator('input[type="file"]').setInputFiles(videoPath);
    await page.getByRole("button", { name: "분석 시작" }).click();

    await expect(page).toHaveURL(/\/result\/.+/, { timeout: 180_000 });
    await expect(page.getByRole("heading", { name: "분석 상세 결과" })).toBeVisible();
    await page.getByRole("button", { name: "이 결과로 AI 코치 상담" }).click();

    await expect(page.getByRole("heading", { name: "로컬 AI 채팅" })).toBeVisible();
    await page
      .getByLabel("AI 코치에게 보낼 메시지")
      .fill("이 발표에서 가장 먼저 연습할 점을 한 문장으로 알려줘.");
    await page.getByRole("button", { name: "전송" }).click();
    await expect(page.locator(".message.assistant")).toHaveCount(1, { timeout: 180_000 });

    await page.getByRole("link", { name: "E2E 사용자" }).click();
    await page.getByLabel("탈퇴 확인 비밀번호").fill(password);
    await page.getByRole("button", { name: "계정 탈퇴" }).click();
    await page.getByRole("button", { name: "계정 삭제" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
