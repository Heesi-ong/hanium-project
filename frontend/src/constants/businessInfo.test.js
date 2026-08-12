import { afterEach, describe, expect, it, vi } from "vitest";

// 값은 모듈이 처음 로드될 때(top-level) 한 번만 계산되므로, env를 바꿔가며 다시
// 읽으려면 매번 모듈 레지스트리를 초기화하고 새로 import해야 한다.
async function importWithEnv(envOverrides) {
    vi.resetModules();
    for (const [key, value] of Object.entries(envOverrides)) {
        vi.stubEnv(key, value);
    }
    return import("./businessInfo");
}

describe("businessInfo", () => {
    afterEach(() => {
        vi.unstubAllEnvs();
    });

    it("falls back to the existing placeholder text when no env vars are set", async () => {
        const businessInfo = await importWithEnv({});

        expect(businessInfo.BUSINESS_NAME).toBe("[실제 서비스명/사업자명 입력 필요]");
        expect(businessInfo.BUSINESS_REGISTRATION_NUMBER)
            .toBe("[실제 사업자등록번호 입력 필요]");
        expect(businessInfo.PRIVACY_OFFICER_EMAIL)
            .toBe("[실제 개인정보 문의 이메일 입력 필요]");
    });

    // P0-02: 실제 정보가 준비되면 코드를 다시 고치지 않고 이 값들만 채우면 되는지가
    // 핵심이므로, env가 실제로 반영되는지 직접 검증한다.
    it("uses the configured value once the matching env var is set", async () => {
        const businessInfo = await importWithEnv({
            VITE_BUSINESS_NAME: "예시 주식회사",
            VITE_BUSINESS_REGISTRATION_NUMBER: "123-45-67890",
        });

        expect(businessInfo.BUSINESS_NAME).toBe("예시 주식회사");
        expect(businessInfo.BUSINESS_REGISTRATION_NUMBER).toBe("123-45-67890");
        // 설정하지 않은 값은 여전히 placeholder를 유지해야 한다.
        expect(businessInfo.BUSINESS_ADDRESS).toBe("[실제 사업장 주소 입력 필요]");
    });

    it("treats a blank/whitespace-only env var the same as unset", async () => {
        const businessInfo = await importWithEnv({ VITE_BUSINESS_PHONE: "   " });

        expect(businessInfo.BUSINESS_PHONE).toBe("[실제 연락처 입력 필요]");
    });
});
