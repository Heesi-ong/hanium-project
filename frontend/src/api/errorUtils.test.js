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

        it("uses frontend defaults for known backend error codes without messages", () => {
            expect(getErrorMessage(
                { error: ERROR_CODES.INVALID_INPUT_VALUE },
                "기본 오류"
            )).toBe("잘못된 요청 값입니다. 입력 내용을 확인해주세요.");

            expect(getErrorMessage(
                { error: ERROR_CODES.UNSUPPORTED_MEDIA_TYPE_ERROR },
                "기본 오류"
            )).toBe("지원하지 않는 요청 형식입니다. Content-Type을 확인해주세요.");

            expect(getErrorMessage(
                { error: ERROR_CODES.FILE_TOO_LARGE },
                "기본 오류"
            )).toBe("업로드 가능한 파일 최대 크기를 초과했습니다.");

            expect(getErrorMessage(
                { error: ERROR_CODES.REQUEST_TIMEOUT },
                "기본 오류"
            )).toBe("요청 시간이 초과되었습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요.");
        });

        it("returns fallback for nullish input or missing message", () => {
            expect(getErrorMessage(null, "기본 오류")).toBe("기본 오류");
            expect(getErrorMessage(undefined, "기본 오류")).toBe("기본 오류");
            expect(getErrorMessage({ error: "UNKNOWN_ERROR" }, "기본 오류"))
                .toBe("기본 오류");
        });

        it("returns fallback when message is an empty string", () => {
            expect(getErrorMessage({ message: "" }, "기본 오류")).toBe("기본 오류");
        });
    });
});
