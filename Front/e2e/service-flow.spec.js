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
    await page.getByRole("radio", { name: /업무 보고/ }).check();
    await page.getByLabel("발표 대상").fill("프로젝트 심사위원");
    await page.getByLabel("반복 연습 이름").fill("한이음 최종 발표");
    await page
      .getByLabel("반드시 전달할 핵심 메시지")
      .fill("발표 코칭 결과를 다음 연습 행동으로 연결합니다.");
    await page.locator('input[type="file"]').setInputFiles(videoPath);
    await page.getByRole("button", { name: "분석 시작" }).click();

    await expect(page).toHaveURL(/\/result\/.+/, { timeout: 180_000 });
    await expect(page.getByRole("heading", { name: "분석 상세 결과" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "업무 보고" })).toBeVisible();
    await expect(page.getByText("측정 불가").first()).toBeVisible();
    await page.locator(".expected-question").first().click();

    await expect(page.getByRole("heading", { name: "로컬 AI 채팅" })).toBeVisible();
    await expect(page.getByText("AI 청중의 예상 질문")).toBeVisible();
    const answerInput = page.getByLabel("AI 코치에게 보낼 메시지");
    await expect(answerInput).toHaveValue("");
    await answerInput.fill(
      "현재 가장 큰 위험은 분석 데이터가 부족한 상황이며, 측정 불가로 구분해 대응합니다.",
    );
    await page.getByRole("button", { name: "전송" }).click();
    await expect(page.locator(".message.assistant")).toHaveCount(1, { timeout: 180_000 });

    await page.goto("/upload");
    await page.getByRole("radio", { name: /업무 보고/ }).check();
    await page.getByLabel("발표 대상").fill("프로젝트 심사위원");
    await page.getByLabel("반복 연습 이름").fill("한이음 최종 발표");
    await page
      .getByLabel("반드시 전달할 핵심 메시지")
      .fill("발표 코칭 결과를 다음 연습 행동으로 연결합니다.");
    await page.locator('input[type="file"]').setInputFiles(videoPath);
    await page.getByRole("button", { name: "분석 시작" }).click();

    await expect(page).toHaveURL(/\/result\/.+/, { timeout: 180_000 });
    await expect(page.getByText("이전 대비")).toBeVisible();
    await expect(page.getByText("+0점")).toBeVisible();

    await page.getByRole("link", { name: "E2E 사용자" }).click();
    await page.getByLabel("탈퇴 확인 비밀번호").fill(password);
    await page.getByRole("button", { name: "계정 탈퇴" }).click();
    await page.getByRole("button", { name: "계정 삭제" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
