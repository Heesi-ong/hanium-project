import { describe, expect, it } from "vitest";

import {
    firstMeaningfulResultValue,
    isMeaningfulResultValue,
} from "./resultDetailFormatters";

describe("resultDetailFormatters", () => {
    it.each([null, undefined, "", "   ", "-", "UNKNOWN"])(
        "treats %s as an empty result metadata value",
        (value) => {
            expect(isMeaningfulResultValue(value)).toBe(false);
        }
    );

    it.each(["REAL", "FALLBACK", "MOCK", "gpt-4.1-mini", 0, false])(
        "treats %s as a meaningful result metadata value",
        (value) => {
            expect(isMeaningfulResultValue(value)).toBe(true);
        }
    );

    it("falls back to the secondary value when the primary value is not meaningful", () => {
        expect(firstMeaningfulResultValue("UNKNOWN", "REAL", "MOCK")).toBe("REAL");
        expect(firstMeaningfulResultValue("-", "gpt-4.1-mini", "mock")).toBe(
            "gpt-4.1-mini"
        );
    });

    it("returns the default value when both metadata values are not meaningful", () => {
        expect(firstMeaningfulResultValue("UNKNOWN", "-", "MOCK")).toBe("MOCK");
    });
});
