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

export function formatEmotionLabel(label) {
    if (label === "neutral") {
        return "중립";
    }

    if (label === "engaged") {
        return "몰입/집중";
    }

    if (label === "speaking") {
        return "발화 중";
    }

    if (label === "low_energy") {
        return "낮은 에너지";
    }

    if (label === "unknown") {
        return "알 수 없음";
    }

    return label || "-";
}

export function formatEyeContactLevel(level) {
    if (level === "good") {
        return "좋음";
    }

    if (level === "normal") {
        return "보통";
    }

    if (level === "weak") {
        return "약함";
    }

    if (level === "poor") {
        return "부족";
    }

    return "알 수 없음";
}

export function formatGazeDirection(direction) {
    if (direction === "left") {
        return "왼쪽";
    }

    if (direction === "right") {
        return "오른쪽";
    }

    if (direction === "center") {
        return "중앙";
    }

    return "알 수 없음";
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

export function formatAnalysisMethod(method) {
    if (method === "duration_based_estimation") {
        return "영상 길이 기반 추정";
    }

    if (method === "audio_extracted_duration_based_estimation") {
        return "오디오 추출 + 길이 기반 추정";
    }

    if (method === "stt_based_analysis") {
        return "STT 기반 분석";
    }

    if (method === "stt_based_filler_detection") {
        return "STT 기반 필러 탐지";
    }

    if (method === "faster_whisper") {
        return "faster-whisper";
    }

    if (method === "mediapipe_pose_wrist_elbow_based") {
        return "MediaPipe 팔/손목 기반";
    }

    if (method === "mediapipe_face_mesh_expression_based") {
        return "MediaPipe Face Mesh 표정 기반";
    }

    if (method === "mediapipe_tasks_pose_landmarker") {
        return "MediaPipe Tasks PoseLandmarker";
    }

    if (method === "mediapipe_tasks_face_landmarker") {
        return "MediaPipe Tasks FaceLandmarker";
    }

    if (method === "mediapipe_tasks_pose_landmarker_wrist_elbow_based") {
        return "MediaPipe Tasks 팔/손목 기반";
    }

    if (method === "mediapipe_tasks_face_landmarker_expression_based") {
        return "MediaPipe Tasks 표정 기반";
    }

    if (method === "mediapipe_tasks_face_landmarker_iris_gaze_ratio") {
        return "MediaPipe Tasks 눈동자(Iris) 응시 비율 기반";
    }

    if (!method) {
        return "-";
    }

    return method;
}
