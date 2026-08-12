import { beforeEach, describe, expect, it } from "vitest";
import {
    clearActiveAnalysisJobId,
    readActiveAnalysisJobId,
    saveActiveAnalysisJobId,
} from "./activeAnalysisStorage";

describe("activeAnalysisStorage", () => {
    beforeEach(() => {
        localStorage.clear();
    });

    it("stores only a valid job id", () => {
        const jobId = "20260809123456-a1b2c3d4";

        expect(saveActiveAnalysisJobId(jobId)).toBe(true);
        expect(readActiveAnalysisJobId()).toBe(jobId);
        expect(localStorage.getItem("presentationCoachActiveAnalysis"))
            .toBe(JSON.stringify({ jobId }));
    });

    it("rejects invalid job ids", () => {
        expect(saveActiveAnalysisJobId("../../another-user")).toBe(false);
        expect(readActiveAnalysisJobId()).toBeNull();
    });

    it("removes malformed persisted data", () => {
        localStorage.setItem("presentationCoachActiveAnalysis", "not-json");

        expect(readActiveAnalysisJobId()).toBeNull();
        expect(localStorage.getItem("presentationCoachActiveAnalysis")).toBeNull();
    });

    it("clears a stored job id", () => {
        saveActiveAnalysisJobId("20260809123456-a1b2c3d4");
        clearActiveAnalysisJobId();

        expect(readActiveAnalysisJobId()).toBeNull();
    });
});

