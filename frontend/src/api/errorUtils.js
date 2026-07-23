export const ERROR_CODES = {
    ANALYSIS_JOB_ACCESS_DENIED: "ANALYSIS_JOB_ACCESS_DENIED",
    ANALYSIS_JOB_NOT_FOUND: "ANALYSIS_JOB_NOT_FOUND",
    ANALYSIS_QUEUE_FULL: "ANALYSIS_QUEUE_FULL",
    VIDEO_LLM_REANALYSIS_NOT_ALLOWED: "VIDEO_LLM_REANALYSIS_NOT_ALLOWED",
    VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE: "VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE",
    VIDEO_LLM_USAGE_LIMIT_EXCEEDED: "VIDEO_LLM_USAGE_LIMIT_EXCEEDED",
    VIDEO_LLM_REAL_REQUIRED: "VIDEO_LLM_REAL_REQUIRED",
    VIDEO_SOURCE_EXPIRED: "VIDEO_SOURCE_EXPIRED",
    FILE_NOT_FOUND: "FILE_NOT_FOUND",
    FILE_TOO_LARGE: "FILE_TOO_LARGE",
    INVALID_INPUT_VALUE: "INVALID_INPUT_VALUE",
    NETWORK_ERROR: "NETWORK_ERROR",
    REQUEST_TIMEOUT: "REQUEST_TIMEOUT",
    TOO_MANY_REQUESTS: "TOO_MANY_REQUESTS",
    UNSUPPORTED_MEDIA_TYPE_ERROR: "UNSUPPORTED_MEDIA_TYPE_ERROR",
};

const ERROR_MESSAGES = {
    [ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED]: "본인이 소유한 분석 작업이 아닙니다.",
    [ERROR_CODES.ANALYSIS_JOB_NOT_FOUND]: "분석 작업을 찾을 수 없습니다.",
    [ERROR_CODES.ANALYSIS_QUEUE_FULL]: "현재 분석 요청이 많아 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요.",
    [ERROR_CODES.VIDEO_LLM_REANALYSIS_NOT_ALLOWED]: "FALLBACK으로 완료된 기본 분석만 실제 Video LLM으로 다시 분석할 수 있습니다.",
    [ERROR_CODES.VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE]: "이미 진행 중인 Video LLM 재분석이 있습니다.",
    [ERROR_CODES.VIDEO_LLM_USAGE_LIMIT_EXCEEDED]: "Video LLM 사용 한도를 초과했습니다. 한도가 갱신된 뒤 다시 시도해주세요.",
    [ERROR_CODES.VIDEO_LLM_REAL_REQUIRED]: "실제 Video LLM을 사용할 수 없어 재분석을 완료하지 못했습니다.",
    [ERROR_CODES.VIDEO_SOURCE_EXPIRED]: "보존 기간이 지나 원본 영상이 삭제되어 재분석할 수 없습니다.",
    [ERROR_CODES.FILE_NOT_FOUND]: "파일을 찾을 수 없습니다.",
    [ERROR_CODES.FILE_TOO_LARGE]: "업로드 가능한 파일 최대 크기를 초과했습니다.",
    [ERROR_CODES.INVALID_INPUT_VALUE]: "잘못된 요청 값입니다. 입력 내용을 확인해주세요.",
    [ERROR_CODES.NETWORK_ERROR]: "서버와 통신할 수 없습니다. 네트워크 연결을 확인해주세요.",
    [ERROR_CODES.REQUEST_TIMEOUT]: "요청 시간이 초과되었습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요.",
    [ERROR_CODES.TOO_MANY_REQUESTS]: "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
    [ERROR_CODES.UNSUPPORTED_MEDIA_TYPE_ERROR]: "지원하지 않는 요청 형식입니다. Content-Type을 확인해주세요.",
};

export function getErrorCode(requestError) {
    return requestError?.error || "";
}

export function getErrorMessage(requestError, fallback) {
    if (requestError?.message) {
        return requestError.message;
    }

    const errorCode = getErrorCode(requestError);

    return ERROR_MESSAGES[errorCode] || fallback;
}
