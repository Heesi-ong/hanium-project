import { describe, expect, it } from "vitest";

import {
    RESULT_SCHEMA_ERROR_CODES,
    RESULT_SCHEMA_VERSIONS,
    validateResultSchemaResponse,
} from "./resultSchema";

function responseWithVersions(topLevelVersion, nestedVersion) {
    const data = { result: { status: "COMPLETED" } };

    if (topLevelVersion !== undefined) {
        data.resultSchemaVersion = topLevelVersion;
    }
    if (nestedVersion !== undefined) {
        data.result.schemaVersion = nestedVersion;
    }

    return { success: true, data };
}

describe("result schema contract", () => {
    it("normalizes an unversioned legacy response as version zero", () => {
        const normalized = validateResultSchemaResponse(
            responseWithVersions(undefined, undefined)
        );

        expect(normalized.data.resultSchemaVersion)
            .toBe(RESULT_SCHEMA_VERSIONS.LEGACY);
        expect(normalized.data.result.schemaVersion)
            .toBe(RESULT_SCHEMA_VERSIONS.LEGACY);
    });

    it("accepts the current version when top-level and stored versions match", () => {
        const normalized = validateResultSchemaResponse(
            responseWithVersions(2, 2)
        );

        expect(normalized.data.resultSchemaVersion)
            .toBe(RESULT_SCHEMA_VERSIONS.CURRENT);
    });

    it("rejects a future version before rendering the result", () => {
        expect(() => validateResultSchemaResponse(responseWithVersions(3, 3)))
            .toThrow(expect.objectContaining({
                error: RESULT_SCHEMA_ERROR_CODES.UNSUPPORTED,
                resultSchemaVersion: 3,
            }));
    });

    it("rejects mismatched top-level and stored versions", () => {
        expect(() => validateResultSchemaResponse(responseWithVersions(2, 0)))
            .toThrow(expect.objectContaining({
                error: RESULT_SCHEMA_ERROR_CODES.INVALID,
            }));
    });

    it.each(["1", -1, 1.5])("rejects an invalid version value %p", (version) => {
        expect(() => validateResultSchemaResponse(responseWithVersions(version, version)))
            .toThrow(expect.objectContaining({
                error: RESULT_SCHEMA_ERROR_CODES.INVALID,
            }));
    });
});
