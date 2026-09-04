export function formatNumber(value, digits = 2) {
    if (value === null || value === undefined || value === "") {
        return "-";
    }

    if (typeof value !== "number") {
        return value;
    }

    return Number.isInteger(value) ? value : value.toFixed(digits);
}

export function formatPercent(value) {
    if (value === null || value === undefined || typeof value !== "number") {
        return "-";
    }

    return `${Math.round(value * 100)}%`;
}

export function formatFileSize(fileSize) {
    if (!fileSize && fileSize !== 0) {
        return "-";
    }

    return `${(fileSize / 1024 / 1024).toFixed(2)}MB`;
}

export function formatDateTime(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return date.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
    });
}

export function formatTimestamp(seconds) {
    if (
        seconds === null ||
        seconds === undefined ||
        typeof seconds !== "number" ||
        Number.isNaN(seconds) ||
        seconds < 0
    ) {
        return "-";
    }

    const normalizedSeconds = Math.floor(seconds);
    const minutes = Math.floor(normalizedSeconds / 60);
    const remainingSeconds = normalizedSeconds % 60;

    return `${String(minutes).padStart(2, "0")}:${String(remainingSeconds).padStart(2, "0")}`;
}

export function formatBoolean(value) {
    if (value === true) {
        return "예";
    }

    if (value === false) {
        return "아니오";
    }

    return "-";
}

export function isMeaningfulResultValue(value) {
    if (value === null || value === undefined) {
        return false;
    }

    const text = String(value).trim();
    return text.length > 0 && text !== "-" && text !== "UNKNOWN";
}

export function firstMeaningfulResultValue(primaryValue, fallbackValue, defaultValue) {
    if (isMeaningfulResultValue(primaryValue)) {
        return primaryValue;
    }

    if (isMeaningfulResultValue(fallbackValue)) {
        return fallbackValue;
    }

    return defaultValue;
}

const EMOTION_LABELS = {
    neutral: "중립",
    engaged: "몰입/집중",
    speaking: "발화 중",
    low_energy: "낮은 에너지",
    unknown: "알 수 없음",
};

export function formatEmotionLabel(label) {
    return EMOTION_LABELS[label] ?? (label || "-");
}

const EYE_CONTACT_LEVEL_LABELS = {
    good: "좋음",
    normal: "보통",
    weak: "약함",
    poor: "부족",
};

export function formatEyeContactLevel(level) {
    return EYE_CONTACT_LEVEL_LABELS[level] ?? "알 수 없음";
}

const GAZE_DIRECTION_LABELS = {
    left: "왼쪽",
    right: "오른쪽",
    center: "중앙",
};

export function formatGazeDirection(direction) {
    return GAZE_DIRECTION_LABELS[direction] ?? "알 수 없음";
}

export function formatSttSuccess(success) {
    if (success === true) {
        return "성공";
    }

    if (success === false) {
        return "실패";
    }

    return "-";
}

// scoreSummary.level(백엔드가 내려주는 원시 값, 예: NORMAL)은 화면에 그대로 노출하지 않고
// 항상 이 함수로 총점에서 다시 계산한 한국어 등급을 보여줍니다 - 결과 상세 화면 안에서
// 등급 표시가 두 곳(상단 배지, 핵심 요약 카드)에 있는데 서로 다른 기준으로 계산되면
// 같은 점수에 다른 등급이 뜨는 모순이 생길 수 있어 기준을 하나로 통일합니다.
export function formatScoreLevel(totalScore) {
    if (typeof totalScore !== "number" || Number.isNaN(totalScore)) {
        return "분석 대기";
    }

    if (totalScore >= 85) {
        return "우수";
    }

    if (totalScore >= 70) {
        return "양호";
    }

    if (totalScore >= 50) {
        return "보통";
    }

    return "개선 필요";
}

export function formatGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "실제 AI 응답";
    }

    if (mode === "FALLBACK") {
        return "AI 호출 실패 후 대체 응답";
    }

    if (mode === "MOCK") {
        return "Mock 응답(AI 미호출)";
    }

    if (mode === "SKIPPED") {
        return "AI 피드백 사용 안 함";
    }

    if (mode === "FAILED") {
        return "생성 실패";
    }

    return "알 수 없음";
}

const ANALYSIS_METHOD_LABELS = {
    duration_based_estimation: "영상 길이 기반 추정",
    audio_extracted_duration_based_estimation: "오디오 추출 + 길이 기반 추정",
    stt_based_analysis: "STT 기반 분석",
    stt_based_filler_detection: "STT 기반 필러 탐지",
    faster_whisper: "faster-whisper",
    mediapipe_pose_wrist_elbow_based: "MediaPipe 팔/손목 기반",
    mediapipe_face_mesh_expression_based: "MediaPipe Face Mesh 표정 기반",
    mediapipe_tasks_pose_landmarker: "MediaPipe Tasks PoseLandmarker",
    mediapipe_tasks_face_landmarker: "MediaPipe Tasks FaceLandmarker",
    mediapipe_tasks_pose_landmarker_wrist_elbow_based: "MediaPipe Tasks 팔/손목 기반",
    mediapipe_tasks_face_landmarker_expression_based: "MediaPipe Tasks 표정 기반",
    mediapipe_tasks_face_landmarker_iris_gaze_ratio: "MediaPipe Tasks 눈동자(Iris) 응시 비율 기반",
};

export function formatAnalysisMethod(method) {
    return ANALYSIS_METHOD_LABELS[method] ?? (method || "-");
}
