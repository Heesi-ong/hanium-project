export const RESULT_SCHEMA_VERSIONS = Object.freeze({
    LEGACY: 0,
    SCORE_WITH_VISUAL_FIELDS: 1,
    CURRENT: 2,
});

export const RESULT_SCHEMA_ERROR_CODES = Object.freeze({
    INVALID: "INVALID_RESULT_SCHEMA",
    UNSUPPORTED: "UNSUPPORTED_RESULT_SCHEMA",
});

const SUPPORTED_RESULT_SCHEMA_VERSIONS = new Set([
    RESULT_SCHEMA_VERSIONS.LEGACY,
    RESULT_SCHEMA_VERSIONS.SCORE_WITH_VISUAL_FIELDS,
    RESULT_SCHEMA_VERSIONS.CURRENT,
]);

function hasOwn(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
}

function createSchemaError(error, message, resultSchemaVersion) {
    return {
        success: false,
        status: 0,
        error,
        message,
        resultSchemaVersion,
        timestamp: new Date().toISOString(),
    };
}

function assertValidVersion(version, source) {
    if (!Number.isInteger(version) || version < 0) {
        throw createSchemaError(
            RESULT_SCHEMA_ERROR_CODES.INVALID,
            `분석 결과의 ${source} 버전 정보가 올바르지 않습니다. 관리자에게 문의해주세요.`,
            version
        );
    }
}

/**
 * 상세 결과 API의 저장 schema 계약을 검증하고 legacy 응답을 명시적 v0 형태로 정규화합니다.
 * 지원하지 않는 미래 버전을 부분 렌더링하면 필드 의미가 달라져 점수·피드백을 오해할 수 있으므로
 * 화면 컴포넌트에 전달하기 전에 중단합니다.
 */
export function validateResultSchemaResponse(apiResponse) {
    const responseData = apiResponse?.data;

    if (!responseData || typeof responseData !== "object" || Array.isArray(responseData)) {
        throw createSchemaError(
            RESULT_SCHEMA_ERROR_CODES.INVALID,
            "분석 결과 응답 형식을 확인할 수 없습니다. 관리자에게 문의해주세요."
        );
    }

    const result = responseData.result;
    if (!result || typeof result !== "object" || Array.isArray(result)) {
        throw createSchemaError(
            RESULT_SCHEMA_ERROR_CODES.INVALID,
            "분석 결과 데이터 형식을 확인할 수 없습니다. 관리자에게 문의해주세요."
        );
    }

    const hasTopLevelVersion = hasOwn(responseData, "resultSchemaVersion");
    const hasNestedVersion = hasOwn(result, "schemaVersion");
    const topLevelVersion = responseData.resultSchemaVersion;
    const nestedVersion = result.schemaVersion;

    if (hasTopLevelVersion) {
        assertValidVersion(topLevelVersion, "최상위");
    }
    if (hasNestedVersion) {
        assertValidVersion(nestedVersion, "저장 결과");
    }
    if (hasTopLevelVersion && hasNestedVersion && topLevelVersion !== nestedVersion) {
        throw createSchemaError(
            RESULT_SCHEMA_ERROR_CODES.INVALID,
            "분석 결과의 버전 정보가 서로 일치하지 않습니다. 관리자에게 문의해주세요.",
            topLevelVersion
        );
    }

    const resolvedVersion = hasTopLevelVersion
        ? topLevelVersion
        : hasNestedVersion
            ? nestedVersion
            : RESULT_SCHEMA_VERSIONS.LEGACY;

    if (!SUPPORTED_RESULT_SCHEMA_VERSIONS.has(resolvedVersion)) {
        throw createSchemaError(
            RESULT_SCHEMA_ERROR_CODES.UNSUPPORTED,
            `이 분석 결과는 현재 화면보다 새로운 형식(v${resolvedVersion})입니다. 서비스 화면을 업데이트한 뒤 다시 확인해주세요.`,
            resolvedVersion
        );
    }

    return {
        ...apiResponse,
        data: {
            ...responseData,
            resultSchemaVersion: resolvedVersion,
            result: {
                ...result,
                schemaVersion: resolvedVersion,
            },
        },
    };
}
