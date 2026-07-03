export const ERROR_CODES = {
    ANALYSIS_JOB_ACCESS_DENIED: "ANALYSIS_JOB_ACCESS_DENIED",
    ANALYSIS_JOB_NOT_FOUND: "ANALYSIS_JOB_NOT_FOUND",
    NETWORK_ERROR: "NETWORK_ERROR",
    TOO_MANY_REQUESTS: "TOO_MANY_REQUESTS",
};

export function getErrorCode(requestError) {
    return requestError?.error || "";
}

export function getErrorMessage(requestError, fallback) {
    return requestError?.message || fallback;
}
