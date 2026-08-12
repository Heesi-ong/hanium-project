const ACTIVE_ANALYSIS_STORAGE_KEY = "presentationCoachActiveAnalysis";
const JOB_ID_PATTERN = /^\d{14}-[0-9a-f]{8}$/;

export function readActiveAnalysisJobId() {
    try {
        const rawValue = window.localStorage.getItem(ACTIVE_ANALYSIS_STORAGE_KEY);

        if (!rawValue) {
            return null;
        }

        const storedValue = JSON.parse(rawValue);

        if (!JOB_ID_PATTERN.test(storedValue?.jobId || "")) {
            clearActiveAnalysisJobId();
            return null;
        }

        return storedValue.jobId;
    } catch {
        clearActiveAnalysisJobId();
        return null;
    }
}

export function saveActiveAnalysisJobId(jobId) {
    if (!JOB_ID_PATTERN.test(jobId || "")) {
        return false;
    }

    try {
        // 파일명·저장 경로 같은 사용자 데이터는 브라우저 영구 저장소에 남기지 않고,
        // 소유권 검증이 적용된 서버 상태 조회에 필요한 식별자만 보관합니다.
        window.localStorage.setItem(
            ACTIVE_ANALYSIS_STORAGE_KEY,
            JSON.stringify({ jobId })
        );
        return true;
    } catch {
        return false;
    }
}

export function clearActiveAnalysisJobId() {
    try {
        window.localStorage.removeItem(ACTIVE_ANALYSIS_STORAGE_KEY);
    } catch {
        // 저장소 사용이 차단된 브라우저에서도 업로드·분석 흐름은 계속 동작해야 합니다.
    }
}

