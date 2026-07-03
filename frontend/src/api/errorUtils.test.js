import { describe, expect, it } from "vitest";

import {
    ERROR_CODES,
    getErrorCode,
    getErrorMessage,
} from "./errorUtils";

describe("errorUtils", () => {
    describe("getErrorCode", () => {
        it("returns backend error code when present", () => {
            expect(getErrorCode({ error: ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED }))
                .toBe(ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED);
        });

        it("returns empty string for nullish or malformed errors", () => {
            expect(getErrorCode(null)).toBe("");
            expect(getErrorCode(undefined)).toBe("");
            expect(getErrorCode({ message: "failed" })).toBe("");
        });
    });

    describe("getErrorMessage", () => {
        it("returns backend message when present", () => {
            expect(getErrorMessage({ message: "요청이 너무 많습니다." }, "fallback"))
                .toBe("요청이 너무 많습니다.");
        });

        it("returns fallback for nullish input or missing message", () => {
            expect(getErrorMessage(null, "기본 오류")).toBe("기본 오류");
            expect(getErrorMessage(undefined, "기본 오류")).toBe("기본 오류");
            expect(getErrorMessage({ error: ERROR_CODES.NETWORK_ERROR }, "기본 오류"))
                .toBe("기본 오류");
        });

        it("returns fallback when message is an empty string", () => {
            expect(getErrorMessage({ message: "" }, "기본 오류")).toBe("기본 오류");
        });
    });
});
